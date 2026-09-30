package com.mindhaven.service.agent;

import com.mindhaven.config.AgentSettings;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.AiGateway;
import com.mindhaven.integration.ai.PromptRepository;
import com.mindhaven.model.ai.AgentStep;
import com.mindhaven.model.ai.AgentTool;
import com.mindhaven.model.ai.Recommendation;
import com.mindhaven.model.chat.ChatEvent;
import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.model.knowledge.RetrievalResult;
import com.mindhaven.observability.TraceSupport;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.AiOperations;
import com.mindhaven.service.chat.CitationVerifier;
import com.mindhaven.service.ai.RunContext;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.dao.TransientDataAccessException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class AgentRunner {
    private final AiOperations ai;
    private final AgentContext context;
    private final AgentToolExecutor tools;
    private final AgentSettings limits;
    private final Settings settings;
    private final PromptRepository prompts;
    private final Tracer tracer;

    public record Result(AiGateway.Result answer, List<Citation> citations, List<Recommendation> recommendations,
                         RetrievalResult retrieval, int contextEstimate, boolean incomplete) {
        public Result(AiGateway.Result answer, List<Citation> citations, List<Recommendation> recommendations,
                      RetrievalResult retrieval, int contextEstimate) {
            this(answer, citations, recommendations, retrieval, contextEstimate, false);
        }
    }

    public AgentRunner(AiOperations ai, AgentToolExecutor tools, AgentSettings limits, Settings settings,
                       PromptRepository prompts, Tracer tracer, AgentContext context) {
        this.context = context;
        this.ai = ai;
        this.tools = tools;
        this.limits = limits;
        this.settings = settings;
        this.prompts = prompts;
        this.tracer = tracer;
    }

    public boolean enabled() { return limits.enabled() && settings.aiMode().equals("live"); }

    public Result run(List<Message> initial, String topic, String version, Consumer<ChatEvent> events) {
        TenantContext.require();
        var restored = context.restoredMessages();
        List<Message> messages = new ArrayList<>(restored == null || restored.isEmpty() ? initial : restored);
        messages.set(0, new SystemMessage(prompts.get("agent").text()));
        var citations = new LinkedHashMap<String, Citation>();
        var recommendations = new LinkedHashMap<String, Recommendation>();
        var matches = new LinkedHashMap<String, RetrievalResult.Match>();
        AgentTaskProgress progress = new AgentTaskProgress();
        String originalRequest = initial.stream().filter(UserMessage.class::isInstance).map(Message::getText).reduce((a, b) -> b).orElse("");
        String restoredGoal = context.originalGoal(originalRequest);
        if (restoredGoal != null) originalRequest = restoredGoal;
        context.save("goal", Map.of("version", 1, "originalRequest", originalRequest));
        Set<String> callIds = new HashSet<>();
        progress.restore(context.restoredCache());
        for (var entry : progress.cacheSnapshot()) {
            entry.result().citations().forEach(c -> citations.putIfAbsent(c.id(), c));
            entry.result().recommendations().forEach(item -> recommendations.putIfAbsent(item.kind() + ":" + item.id(), item));
        }
        int calls = 0, searches = 0, peak = 0, inputTokens = 0, outputTokens = 0;
        boolean knownUsage = true;
        int citationRepairs = 0;
        RetrievalResult.Configuration retrievalConfig = null;
        // Current request remains a user message through compaction. Reattach the original goal on resume.
        if (RunContext.parentId() != null) messages.set(messages.size() - 1,
                new UserMessage("原任务要求（用户数据）：" + originalRequest + "\n继续完成该任务，不重复已完成内容。"));
        context.save("cache", progress.cacheSnapshot());
        for (int step = 1; step <= limits.maxSteps(); step++) {
            RunContext.check();
            var available = new ArrayList<>(tools.definitions());
            boolean allowTools = step < limits.maxSteps()
                    && (limits.maxToolCalls() == 0 || calls < limits.maxToolCalls()) && !progress.stalled();
            List<AgentTool> definitions = allowTools ? List.copyOf(available) : List.of();
            // Prepare with schemas and result headroom before deciding whether tools fit.
            String state = calls == 0 ? "本轮尚未执行任何查询。历史课程不是本轮新查询结果。" : "本轮已尝试工具调用，仅成功的工具结果可作为当前查询依据。";
            if (!allowTools) state += "本轮已进入收尾阶段（额度、工具调用或步数限制），不能再查；必须说明限制，不得声称刚查过、没有新课程或列表完整。";
            messages.set(0, new SystemMessage(prompts.get("agent").text() + "\n本轮执行状态：" + state));
            if (!allowTools) events.accept(new ChatEvent.AgentStatus("finalizing", "正在整理已确认的结果…", step));
            try { context.prepare(messages, definitions, step, allowTools ? limits.toolResultBudget() : 0); }
            catch (AgentContext.ContextCapacityException e) {
                return partial(events, citations, recommendations, matches, retrievalConfig, peak, "可用上下文或任务额度不足");
            }
            if (allowTools && (long) AgentMessageBudget.estimate(messages, definitions) + settings.outputBudget()
                    + AgentMessageBudget.estimate(messages, List.of()) + settings.outputBudget()
                    + limits.toolResultBudget() + 1200 > RunContext.remaining()) {
                allowTools = false;
                definitions = List.of();
                messages.set(0, new SystemMessage(prompts.get("agent").text()
                        + "\n本轮进入额度收尾：不能继续查询；仅依据已成功返回的结果回答，明确未完成部分，不得声称重新查过或结果完整。"));
                events.accept(new ChatEvent.AgentStatus("finalizing", "正在整理已确认的结果…", step));
                try { context.prepare(messages, definitions, step, 0); }
                catch (AgentContext.ContextCapacityException e) {
                    return partial(events, citations, recommendations, matches, retrievalConfig, peak, "收尾容量不足");
                }
            }
            context.checkpoint(step, messages);
            peak = Math.max(peak, AgentMessageBudget.estimate(messages, definitions));
            events.accept(new ChatEvent.AgentStatus("model", allowTools ? "正在理解你的需要…" : "正在整理回复…", step));
            AgentStep response;
            var span = tracer.spanBuilder("agent.model").startSpan();
            span.setAttribute("agent.step", step);
            if (RunContext.id() != null) span.setAttribute("run.id", RunContext.id());
            span.setAttribute("agent.tools_enabled", allowTools);
            span.setAttribute("ai.model", settings.chatModel());
            span.setAttribute("ai.prompt_hash", prompts.get("agent").hash());
            try (var scope = new TraceSupport(span)) {
                try { response = ai.streamStep(List.copyOf(messages), settings.outputBudget(), definitions, text -> events.accept(new ChatEvent.Delta(text))); }
                catch (RuntimeException e) {
                    scope.failed(e);
                    if (!contextOverflow(e)) throw e;
                    events.accept(new ChatEvent.AnswerReset());
                    events.accept(new ChatEvent.AgentStatus("compacting", "正在缩减上下文后重试…", step));
                    if (!context.recover(messages, step))
                        return partial(events, citations, recommendations, matches, retrievalConfig, peak, "模型上下文无法继续缩减");
                    try {
                        context.prepare(messages, definitions, step, 0);
                        context.checkpoint(step, messages);
                        response = ai.streamStep(List.copyOf(messages), settings.outputBudget(), definitions,
                                text -> events.accept(new ChatEvent.Delta(text)));
                    } catch (RuntimeException retry) {
                        if (!(retry instanceof AgentContext.ContextCapacityException) && !contextOverflow(retry)) throw retry;
                        return partial(events, citations, recommendations, matches, retrievalConfig, peak, "缩减上下文后仍无法继续");
                    }
                }
            }
            if (response.promptTokens() == null || response.completionTokens() == null) knownUsage = false;
            else { inputTokens += response.promptTokens(); outputTokens += response.completionTokens(); }
            context.save("model-output-" + step, Map.of("finishReason", response.finishReason() == null ? "unknown" : response.finishReason(),
                    "content", response.message().getText() == null ? "" : response.message().getText(),
                    "toolCalls", response.message().getToolCalls()));
            if ("length".equals(response.finishReason()) && !response.message().hasToolCalls()
                    && response.message().getText() != null && !response.message().getText().isBlank()) {
                messages.add(response.message());
                context.checkpoint(step, messages);
                context.save("resumable", true);
                String notice = "\n\n【本轮达到输出长度限制，以上内容尚未全部完成，可继续生成。】";
                events.accept(new ChatEvent.Delta(notice));
                events.accept(new ChatEvent.AgentStatus("partial", "已保留正文，输出尚未完成", step));
                events.accept(new ChatEvent.Sources(List.copyOf(citations.values())));
                events.accept(new ChatEvent.Recommendations(List.copyOf(recommendations.values())));
                return new Result(new AiGateway.Result(response.message().getText() + notice,
                        knownUsage ? inputTokens : null, knownUsage ? outputTokens : null),
                        List.copyOf(citations.values()), List.copyOf(recommendations.values()),
                        new RetrievalResult("agent", List.copyOf(citations.values()), List.copyOf(matches.values()), retrievalConfig), peak, true);
            }
            if (!Set.of("stop", "tool_calls").contains(response.finishReason() == null ? "" : response.finishReason()))
                throw new IllegalStateException("模型未正常结束，不能将截断响应视为完成");
            var assistant = response.message();
            if (!assistant.hasToolCalls()) {
                if (!"stop".equals(response.finishReason())) throw new IllegalStateException("模型未正常结束");
                String answer;
                try { answer = AgentCompletion.answer(assistant.getText()); }
                catch (IllegalArgumentException e) {
                    events.accept(new ChatEvent.AnswerReset());
                    if (!allowTools) return partial(events, citations, recommendations, matches, retrievalConfig, peak, "本轮未能生成完整回答");
                    messages.add(new SystemMessage(e.getMessage() + "。请重新给出完整有效回答，不要只补充标点。"));
                    continue;
                }
                var citationCheck = new CitationVerifier().verify(answer, List.copyOf(citations.values()));
                if (!citationCheck.invalidIds().isEmpty()) {
                    events.accept(new ChatEvent.AnswerReset());
                    if (++citationRepairs > 2 || step == limits.maxSteps()) {
                        return partial(events, citations, recommendations, matches, retrievalConfig, peak,
                                "引用尚未核实，未将该回答作为已确认结果");
                    }
                    // Keep the rejected draft as data so the model can correct the exact claims.
                    messages.add(assistant);
                    messages.add(new SystemMessage("上一份回答引用校验未通过。以下 ID 不属于本任务已确认来源："
                            + citationCheck.invalidIds() + "。当前允许引用的 ID：" + citations.keySet()
                            + "。历史回答中的引用不构成本轮证据。请使用可用工具重新核实相关原文；"
                            + "若无法核实，撤回对应来源归因及无依据的具体说法，明确资料局限。"
                            + "不要仅替换成另一个 ID。请重新输出完整回答，不要只补充标点。"));
                    continue;
                }
                RunContext.check();
                context.save("completed", Map.of("step", step, "answer", answer));
                events.accept(new ChatEvent.Sources(List.copyOf(citations.values())));
                events.accept(new ChatEvent.Recommendations(List.copyOf(recommendations.values())));
                return new Result(new AiGateway.Result(answer, knownUsage ? inputTokens : null, knownUsage ? outputTokens : null),
                        List.copyOf(citations.values()), List.copyOf(recommendations.values()),
                        new RetrievalResult("agent", List.copyOf(citations.values()), List.copyOf(matches.values()), retrievalConfig), peak);
            }
            events.accept(new ChatEvent.AnswerReset());
            if (!allowTools || !"tool_calls".equals(response.finishReason())) throw new IllegalStateException("模型违反工具调用边界");
            // Validate the complete batch before executing its first tool.
            var batch = assistant.getToolCalls();
            for (var call : batch) {
                if (call.id() == null || call.id().isBlank() || !callIds.add(call.id()))
                    throw new IllegalArgumentException("工具调用标识无效");
            }
            long batchSearches = batch.stream().filter(call -> "searchKnowledge".equals(call.name())).count();
            if ((limits.maxToolCalls() > 0 && batch.size() > limits.maxToolCalls() - calls) || (limits.maxSearches() > 0 && batchSearches > limits.maxSearches() - searches)) {
                var rejected = new ArrayList<ToolResponse>();
                for (var call : batch) {
                    rejected.add(toolError(call.id(), call.name(), "TOOL_CALL_LIMIT",
                            "本批调用超过剩余次数，整批未执行。请基于已确认结果收尾，说明未完成部分，不得声称查询成功。", false));
                }
                messages.add(assistant);
                messages.add(ToolResponseMessage.builder().responses(rejected).build());
                context.save("rejected-calls-" + step, batch);
                events.accept(new ChatEvent.AgentStatus("tool-error", "查询次数受限，正在整理已有结果…", step));
                calls = limits.maxToolCalls();
                progress.repeated(); progress.repeated(); progress.repeated();
                continue;
            }
            context.save("calls-" + step, batch);
            calls += batch.size();
            searches += (int) batchSearches;
            var responses = new ArrayList<ToolResponse>();
            for (var call : batch) {
                RunContext.check();
                events.accept(new ChatEvent.AgentStatus("tool", toolLabel(call.name()), step, call.name(), call.id(), call.arguments()));
                AgentToolExecutor.Result result;
                String cacheKey = progress.key(call.name(), call.arguments());
                var cached = progress.cached(cacheKey);
                if (cached != null) {
                    progress.repeated();
                    responses.add(new ToolResponse(call.id(), call.name(), progress.cachedPayload(cached)));
                    context.save("result-" + call.id(), Map.of("source", "task_cache", "result", cached));
                    events.accept(new ChatEvent.AgentStatus("cache-hit", "重复查询，已复用本任务的结果", step, call.name(), call.id(), call.arguments()));
                    continue;
                }
                var toolSpan = tracer.spanBuilder("agent.tool").startSpan();
                toolSpan.setAttribute("agent.tool", toolLabel(call.name()));
                toolSpan.setAttribute("agent.step", step);
                if (RunContext.id() != null) toolSpan.setAttribute("run.id", RunContext.id());
                try (var scope = new TraceSupport(toolSpan)) {
                    try { result = tools.execute(call.name(), call.arguments(), topic, version); }
                    catch (ToolArgumentException e) {
                        scope.failed(e);
                        progress.failed("INVALID_ARGUMENTS");
                        String feedback = JsonNodeFactory.instance.objectNode()
                                .put("status", "error").put("code", "INVALID_ARGUMENTS")
                                .put("message", e.getMessage()).put("retryable", true).toString();
                        context.save("result-" + call.id(), Map.of("status", "INVALID_ARGUMENTS", "payload", feedback));
                        responses.add(new ToolResponse(call.id(), call.name(), feedback));
                        events.accept(new ChatEvent.AgentStatus("tool-error", "查询参数需要修正，正在反馈给模型…", step, call.name(), call.id(), call.arguments()));
                        continue;
                    }
                    catch (RuntimeException e) {
                        scope.failed(e);
                        RunContext.check();
                        if (!(e instanceof ResourceAccessException)
                                && !(e instanceof TransientDataAccessException)) throw e;
                        responses.add(toolError(call.id(), call.name(), "TOOL_UNAVAILABLE",
                                "工具暂时不可用，本次没有取得有效结果。可在剩余次数内重试，或明确说明无法核实。", true));
                        progress.failed("TOOL_UNAVAILABLE");
                        events.accept(new ChatEvent.AgentStatus("tool-error", "查询暂时不可用，正在反馈给模型…", step, call.name(), call.id(), call.arguments()));
                        continue;
                    }
                    toolSpan.setAttribute("agent.result_count", result.citations().size() + result.recommendations().size());
                }
                context.save("result-" + call.id(), result);
                progress.confirmed(cacheKey, result);
                result.citations().forEach(c -> citations.putIfAbsent(c.id(), c));
                result.recommendations().forEach(item -> recommendations.putIfAbsent(item.kind() + ":" + item.id(), item));
                if (result.retrieval() != null) {
                    result.retrieval().matches().forEach(match -> matches.putIfAbsent(match.chunkId(), match));
                    retrievalConfig = result.retrieval().configuration();
                }
                responses.add(new ToolResponse(call.id(), call.name(), result.payload()));
            }
            messages.add(assistant);
            messages.add(ToolResponseMessage.builder().responses(responses).build());
            context.save("progress", progress.snapshot());
            context.save("cache", progress.cacheSnapshot());
            context.checkpoint(step, messages);
            if (progress.stalled()) messages.add(new SystemMessage("连续工具调用没有取得新进展。请基于真实结果收尾并说明未完成部分，不要继续重复查询。"));
        }
        return partial(events, citations, recommendations, matches, retrievalConfig, peak, "本轮执行次数已达上限");
    }

    private ToolResponse toolError(String id, String name, String code, String message, boolean retryable) {
        String payload = JsonNodeFactory.instance.objectNode().put("status", "error")
                .put("code", code).put("message", message).put("retryable", retryable).toString();
        context.save("result-" + id, Map.of("status", code, "payload", payload));
        return new ToolResponse(id, name, payload);
    }

    private boolean contextOverflow(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            String message = error.getMessage();
            if (message == null) continue;
            String value = message.toLowerCase(Locale.ROOT);
            if (value.contains("context_length_exceeded") || value.contains("maximum context length")
                    || value.contains("context window exceeded")) return true;
        }
        return false;
    }

    private Result partial(Consumer<ChatEvent> events, Map<String, Citation> citations,
                           Map<String, Recommendation> recommendations,
                           Map<String, RetrievalResult.Match> matches,
                           RetrievalResult.Configuration configuration, int peak, String reason) {
        RunContext.check();
        StringBuilder text = new StringBuilder("本轮未能完成全部请求，已停止继续查询。");
        if (!recommendations.isEmpty()) {
            text.append("以下是本轮已查询确认的部分资源，尚未完成的查询不代表没有更多结果：");
            recommendations.values().forEach(item -> text.append("\n- ").append(item.title()));
        } else if (!citations.isEmpty()) {
            text.append("已找到部分参考资料，但尚未完成分析：");
            citations.values().forEach(item -> text.append("\n- ").append(item.title()).append(" [").append(item.id()).append("]"));
        } else text.append("本轮没有可展示的已确认资源，不能据此判断资源不存在。请缩小查询范围后继续。");
        context.save("resumable", true);
        context.save("partial", Map.of("reason", reason, "answer", text.toString(),
                "resourceIds", recommendations.keySet(), "citationIds", citations.keySet()));
        events.accept(new ChatEvent.AnswerReset());
        events.accept(new ChatEvent.AgentStatus("partial", "已保留部分结果，本轮未全部完成", 0));
        events.accept(new ChatEvent.Delta(text.toString()));
        events.accept(new ChatEvent.Sources(List.copyOf(citations.values())));
        events.accept(new ChatEvent.Recommendations(List.copyOf(recommendations.values())));
        return new Result(new AiGateway.Result(text.toString(), null, null), List.copyOf(citations.values()),
                List.copyOf(recommendations.values()),
                new RetrievalResult("agent", List.copyOf(citations.values()), List.copyOf(matches.values()), configuration), peak, true);
    }

    private String toolLabel(String name) {
        return switch (name) {
            case "searchKnowledge" -> "正在查找参考资料…";
            case "findCourses" -> "正在查找已发布课程…";
            case "listAvailableSurveys" -> "正在查找可填写问卷…";
            default -> "正在检查工具权限…";
        };
    }
}
