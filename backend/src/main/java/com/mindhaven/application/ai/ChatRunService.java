package com.mindhaven.application.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.application.chat.ChatService;
import com.mindhaven.application.dto.ChatCommand;
import com.mindhaven.common.Hashes;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.common.observability.TraceSupport;
import com.mindhaven.domain.model.AiRun;
import com.mindhaven.domain.port.RunRepository;
import com.mindhaven.security.TenantContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class ChatRunService implements AutoCloseable {
  private final Tracer tracer;
  private final ChatService chat;
  private final RunRepository runs;
  private final ObjectMapper json;
  private final int deadlineSeconds;
  private final int tokenBudget;
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

  private static class Job {
    final long queuedAt = System.nanoTime();
    final AtomicBoolean cancelled = new AtomicBoolean();
    volatile FutureTask<Void> task;
    volatile ScheduledFuture<?> timeout;
  }

  public ChatRunService(
      Tracer tracer,
      ChatService chat,
      RunRepository runs,
      ObjectMapper json,
      @Value("${mindhaven.runtime.deadline-seconds:180}") int deadlineSeconds,
      @Value("${mindhaven.runtime.token-budget:24000}") int tokenBudget) {
    this.tracer = tracer;
    this.chat = chat;
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
    chat.session(session);
    if (input.requestId() == null || input.requestId().isBlank())
      throw new HttpProblem(400, "缺少请求标识");
    String hash;
    try {
      hash =
          Hashes.sha256(
              json.writeValueAsString(
                  new ChatCommand(
                      input.message(),
                      input.topic(),
                      input.version(),
                      input.rewrite(),
                      input.compression(),
                      null)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    var created = runs.create(session, input.requestId(), hash, input.message());
    if (!created.fresh()) return created.run();
    var identity = TenantContext.require();
    String id = created.run().id();
    Job job = new Job();
    Context parent = Context.current();
    job.task =
        new FutureTask<>(
            () -> {
              try (var parentScope = parent.makeCurrent()) {
                execute(id, input, identity, job);
              }
              return null;
            });
    jobs.put(id, job);
    try {
      job.timeout =
          timer.schedule(
              () -> {
                try (var scope = TenantContext.open(identity)) {
                  cancel(id);
                }
              },
              deadlineSeconds,
              TimeUnit.SECONDS);
      workers.execute(job.task);
    } catch (RejectedExecutionException e) {
      jobs.remove(id);
      if (job.timeout != null) job.timeout.cancel(false);
      runs.finish(id, AiRun.Status.FAILED, "error", Map.of("message", "任务队列已满，请稍后创建新请求"), "任务队列已满");
      throw new HttpProblem(429, "任务队列已满，请稍后重试");
    }
    return runs.get(id);
  }

  private void execute(String id, ChatCommand input, TenantContext.Identity identity, Job job) {
    var span = tracer.spanBuilder("ai.run").setAttribute("run.id", id).startSpan();
    span.setAttribute("run.queue_ms", (System.nanoTime() - job.queuedAt) / 1_000_000);
    span.addEvent("run.started");
    try (var trace = new TraceSupport(span)) {
      executeBody(id, input, identity, job);
      try (var identityScope = TenantContext.open(identity)) {
        var status = runs.get(id).status();
        span.setAttribute("run.status", status.name());
        span.addEvent("run." + status.name().toLowerCase(java.util.Locale.ROOT));
        if (status == AiRun.Status.FAILED)
          span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
      }
    }
  }

  private void executeBody(String id, ChatCommand input, TenantContext.Identity identity, Job job) {
    try (var scope = TenantContext.open(identity);
        var context = RunContext.open(id, job.cancelled::get, tokenBudget)) {
      if (!runs.start(id)) throw new CancellationException();
      StringBuilder pending = new StringBuilder();
      long[] last = {0};
      chat.turn(
          runs.get(id).sessionId(),
          input.message(),
          input.topic(),
          input.version(),
          input.rewrite(),
          input.compression(),
          (name, data) -> {
            RunContext.check();
            if (name.equals("delta")) {
              pending.append(((Map<?, ?>) data).get("text"));
              long now = System.nanoTime();
              if (last[0] == 0 || pending.length() >= 48 || now - last[0] >= 100_000_000L) {
                runs.append(id, "delta", Map.of("text", pending.toString()));
                pending.setLength(0);
                last[0] = now;
              }
            } else {
              if (!pending.isEmpty()) {
                runs.append(id, "delta", Map.of("text", pending.toString()));
                pending.setLength(0);
              }
              if (!name.equals("done")) runs.append(id, name, data);
            }
          },
          result -> {
            if (!pending.isEmpty()) {
              runs.append(id, "delta", Map.of("text", pending.toString()));
              pending.setLength(0);
            }
            runs.finish(id, AiRun.Status.COMPLETED, "done", result, null);
          });
    } catch (Exception e) {
      Thread.interrupted();
      try (var scope = TenantContext.open(identity)) {
        boolean cancelled = job.cancelled.get() || e instanceof CancellationException;
        String error =
            cancelled
                ? "任务已停止，已生成的部分内容仍可查看"
                : e instanceof IllegalArgumentException ? e.getMessage() : "生成未完成，请查看任务状态后重试";
        runs.finish(
            id,
            cancelled ? AiRun.Status.CANCELLED : AiRun.Status.FAILED,
            "error",
            Map.of("message", error),
            error);
        if (!cancelled) {
          Throwable cause = e;
          while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
          // Log class and call sites, never request bodies, provider responses or credentials.
          org.slf4j.LoggerFactory.getLogger(getClass())
              .warn(
                  "AI run {} failed ({}, root {} at {})",
                  id,
                  e.getClass().getSimpleName(),
                  cause.getClass().getSimpleName(),
                  Arrays.stream(cause.getStackTrace()).limit(4).toList());
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
    chat.session(session);
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
    runs.finish(
        id, AiRun.Status.CANCELLED, "error", Map.of("message", "任务已停止，已生成的部分内容仍可查看"), "任务已停止");
    return runs.get(id);
  }

  @Override
  @PreDestroy
  public void close() {
    workers.shutdownNow();
    timer.shutdownNow();
  }
}
