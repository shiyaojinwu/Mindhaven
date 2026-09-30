package com.mindhaven.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.common.Hashes;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.manager.RunManager;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.service.event.RunEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import com.mindhaven.model.chat.AiRun;
import com.mindhaven.model.dto.ChatCommand;
import com.mindhaven.observability.TraceSupport;
import com.mindhaven.security.LoginIdentity;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.chat.ChatService;
import com.mindhaven.service.chat.SessionService;
import com.mindhaven.model.chat.ChatEvent;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatRunService implements AutoCloseable {
    private final Tracer tracer;
    private final ChatService chat;
    private final SessionService sessions;
    private final RunManager runs;
    private final ObjectMapper json;
    private final int deadlineSeconds;
    private final int tokenBudget;
    private RecordManager records;
    private RunEventPublisher eventPublisher;
    @Autowired
    public void eventPublisher(RunEventPublisher eventPublisher) { this.eventPublisher = eventPublisher; }
    @Autowired
    public void records(RecordManager records) { this.records = records; }
    public record Continuation(ChatCommand command, String parentId, int remaining, long elapsedMillis) { }

    public synchronized AiRun continueRun(String id) {
        var previous = runs.get(id);
        String requestId = "continue:" + id;
        var priorChild = runs.recent(previous.sessionId()).stream().filter(run -> requestId.equals(run.requestId())).findFirst();
        if (priorChild.isPresent()) return priorChild.get();
        if (jobs.containsKey(id) || previous.status() != AiRun.Status.COMPLETED) throw new HttpProblem(409, "任务仍在结束处理中，请稍后继续");
        if (!runs.recent(previous.sessionId()).getFirst().id().equals(id)) throw new HttpProblem(409, "会话已有新任务，请按当前要求继续");
        if (!records.get("agent-state", id + ":resumable", Boolean.class).orElse(false))
            throw new HttpProblem(409, "此任务没有可续跑的检查点");
        var checkpoint = records.get("run-continuations", id, Continuation.class).orElseThrow();
        if (checkpoint.remaining() <= 1000 || checkpoint.elapsedMillis() >= deadlineSeconds * 1000L)
            throw new HttpProblem(409, "原任务总额度或总时限已用尽，已保留结果；请调整任务范围后新建请求");
        var command = new ChatCommand("继续完成原任务", checkpoint.command().topic(), checkpoint.command().version(), false, requestId);
        return create(previous.sessionId(), command, new Continuation(checkpoint.command(), id, checkpoint.remaining(), checkpoint.elapsedMillis()));
    }
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    private static class Job {
        final long queuedAt = System.nanoTime();
        volatile boolean timedOut;
        final AtomicBoolean cancelled = new AtomicBoolean();
        Continuation continuation;
        volatile FutureTask<Void> task;
        volatile ScheduledFuture<?> timeout;
    }

    public ChatRunService(Tracer tracer, ChatService chat, SessionService sessions, RunManager runs, ObjectMapper json, @Value("${mindhaven.runtime.deadline-seconds:180}") int deadlineSeconds, @Value("${mindhaven.runtime.token-budget:1500000}") int tokenBudget) {
        this.tracer = tracer;
        this.chat = chat;
        this.sessions = sessions;
        this.runs = runs;
        this.json = json;
        this.deadlineSeconds = deadlineSeconds;
        this.tokenBudget = tokenBudget;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        runs.recoverInterrupted();
    }

    public AiRun create(String session, ChatCommand input) {
        return create(session, input, new Continuation(input, null, tokenBudget, 0));
    }

    private AiRun create(String session, ChatCommand input, Continuation continuation) {
        sessions.session(session);
        if (input.requestId() == null || input.requestId().isBlank()) throw new HttpProblem(400, "缺少请求标识");
        String hash;
        try {
            hash = Hashes.sha256(json.writeValueAsString(new ChatCommand(input.message(), input.topic(), input.version(), input.rewrite(), null)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        var created = runs.create(session, input.requestId(), hash, input.message());
        // 幂等去重：requestId/内容哈希已存在同一条运行记录时，说明是重复请求（如前端重试/重复点击），
        // 直接返回已有的 run，不再重复提交异步任务，避免重复调用大模型。
        if (!created.fresh()) return created.run();
        // 提前取出当前线程的租户身份：下面会把任务提交到独立线程池异步执行，
        // 异步线程没有当前请求的 ThreadLocal 上下文，必须显式传递 identity 供其内部重新绑定。
        var identity = TenantContext.require();
        String id = created.run().id();
        Job job = new Job();
        job.continuation = continuation;
        records.put("run-continuations", id, continuation);
        Context parent = Context.current();
        job.task = new FutureTask<>(() -> {
            try (var parentScope = parent.makeCurrent()) {
                execute(id, input, identity, job);
            }
            return null;
        });
        jobs.put(id, job);
        try {
            job.timeout = timer.schedule(() -> {
                try (var scope = TenantContext.open(identity)) {
                    job.timedOut = true;
                    cancel(id);
                }
            }, Math.max(1, deadlineSeconds * 1000L - continuation.elapsedMillis()), TimeUnit.MILLISECONDS);
            workers.execute(job.task);
        } catch (RejectedExecutionException e) {
            jobs.remove(id);
            if (job.timeout != null) job.timeout.cancel(false);
            runs.finish(id, AiRun.Status.FAILED, "error", Map.of("message", "任务队列已满，请稍后创建新请求"), "任务队列已满");
            throw new HttpProblem(429, "任务队列已满，请稍后重试");
        }
        return runs.get(id);
    }

    private void execute(String id, ChatCommand input, LoginIdentity identity, Job job) {
        var span = tracer.spanBuilder("ai.run").setAttribute("run.id", id).startSpan();
        span.setAttribute("run.queue_ms", (System.nanoTime() - job.queuedAt) / 1_000_000);
        span.addEvent("run.started");
        try (var trace = new TraceSupport(span)) {
            executeBody(id, input, identity, job);
            try (var identityScope = TenantContext.open(identity)) {
                var status = runs.get(id).status();
                span.setAttribute("run.status", status.name());
                span.addEvent("run." + status.name().toLowerCase(Locale.ROOT));
                if (status == AiRun.Status.FAILED) span.setStatus(StatusCode.ERROR);
            }
        }
    }

    private void executeBody(String id, ChatCommand input, LoginIdentity identity, Job job) {
        try (var scope = TenantContext.open(identity); var context = RunContext.open(id, job.cancelled::get, job.continuation.remaining(), job.continuation.parentId())) {
            try {
            if (!runs.start(id)) throw new CancellationException();
            try (var events = eventPublisher.open(id)) {
                chat.turn(runs.get(id).sessionId(), input.message(), input.topic(), input.version(), input.rewrite(), event -> {
                    RunContext.check();
                    events.accept(event);
                }, result -> {
                    events.flush();
                    runs.finish(id, AiRun.Status.COMPLETED, "done", result, null);
                });
            }
            } finally {
                records.put("run-continuations", id, new Continuation(job.continuation.command(), job.continuation.parentId(),
                        RunContext.remaining(), job.continuation.elapsedMillis() + (System.nanoTime() - job.queuedAt) / 1_000_000));
            }
        } catch (Exception e) {
            Thread.interrupted();
            try (var scope = TenantContext.open(identity)) {
                boolean cancelled = job.cancelled.get() || e instanceof CancellationException;
                String error = cancelled ? (job.timedOut ? "任务总时限已到，已生成内容已保留；请缩小范围后新建任务" : "任务已停止，已生成的部分内容仍可查看") : e instanceof IllegalArgumentException ? e.getMessage() : "生成未完成，请查看任务状态后重试";
                runs.finish(id, cancelled ? AiRun.Status.CANCELLED : AiRun.Status.FAILED, "error", Map.of("message", error), error);
                if (!cancelled) {
                    Throwable cause = e;
                    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                    // Log class and call sites, never request bodies, provider responses or credentials.
                    LoggerFactory.getLogger(getClass()).warn("AI run {} failed ({}, root {} at {})", id, e.getClass().getSimpleName(), cause.getClass().getSimpleName(), Arrays.stream(cause.getStackTrace()).limit(4).toList());
                }
            }
        } finally {
            jobs.remove(id);
            if (job.timeout != null) job.timeout.cancel(false);
        }
    }

    public AiRun get(String id) {
        return runs.get(id);
    }

    public List<AiRun> recent(String session) {
        sessions.session(session);
        return runs.recent(session);
    }

    public List<AiRun.Event> events(String id, long after) {
        return runs.events(id, after);
    }

    public AiRun cancel(String id) {
        var run = runs.get(id);
        if (run.status().terminal()) return run;
        runs.requestCancel(id);
        Job job = jobs.remove(id);
        if (job != null) {
            job.cancelled.set(true);
            job.task.cancel(true);
            workers.remove(job.task);
            if (job.timeout != null) job.timeout.cancel(false);
        }
        // Publish cancellation immediately; provider interruption remains best effort.
        String notice = job != null && job.timedOut ? "任务总时限已到，已生成内容已保留；请缩小范围后新建任务" : "任务已停止，已生成的部分内容仍可查看";
        runs.finish(id, AiRun.Status.CANCELLED, "error", Map.of("message", notice), notice);
        return runs.get(id);
    }

    @Override
    @PreDestroy
    public void close() {
        workers.shutdownNow();
        timer.shutdownNow();
    }
}
