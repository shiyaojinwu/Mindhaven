package com.mindhaven.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.PromptRepository;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.model.ai.AgentTool;
import com.mindhaven.service.ai.AiOperations;
import com.mindhaven.service.ai.RunContext;
import org.springframework.ai.chat.messages.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Durable execution evidence is separate from the bounded model view. */
@Component
public class AgentContext {
    private final RecordManager records;
    private final AiOperations ai;
    private final PromptRepository prompts;
    private final Settings settings;
    private final ObjectMapper json;

    public AgentContext(RecordManager records, AiOperations ai, PromptRepository prompts, Settings settings, ObjectMapper json) {
        this.records = records;
        this.ai = ai;
        this.prompts = prompts;
        this.settings = settings;
        this.json = json;
    }

    public void save(String key, Object state) {
        if (RunContext.id() != null) records.put("agent-state", RunContext.id() + ":" + key, state);
    }

    public void checkpoint(int step, List<Message> messages) {
        save("model-input-" + step, snapshot(messages));
    }

    public void prepare(List<Message> messages, List<AgentTool> tools, int step) {
        prepare(messages, tools, step, 0);
    }

    public void prepare(List<Message> messages, List<AgentTool> tools, int step, int reserve) {
        int limit = Math.min(settings.contextBudget() - settings.outputBudget() - 600,
                RunContext.remaining() - settings.outputBudget() - 200);
        // Headroom is a compaction target, not capacity already consumed by a future call.
        int trigger = Math.max(0, Math.min(limit - reserve, (int) (settings.contextBudget() * 0.8)));
        if (AgentMessageBudget.estimate(messages, tools) < trigger) return;
        save("before-context-" + step, snapshot(messages));
        trigger = Math.min(trigger, (int) (limit * 0.6));
        // Remove lowest-priority whole items; never cut JSON or break tool call/result pairing.
        for (int i = 0; i < messages.size() && AgentMessageBudget.estimate(messages, tools) >= trigger; i++) {
            if (!(messages.get(i) instanceof ToolResponseMessage response)) continue;
            var reduced = new ArrayList<ToolResponseMessage.ToolResponse>();
            for (var result : response.getResponses()) {
                String payload = result.responseData();
                try {
                    var tree = json.readTree(payload);
                    if (tree instanceof ObjectNode object) {
                        for (String field : List.of("documents", "items")) {
                            if (object.get(field) instanceof ArrayNode items && items.size() > 1) {
                                while (items.size() > 1) items.remove(items.size() - 1);
                                object.put("contextCompacted", true);
                                if (field.equals("items")) {
                                    object.put("returned", items.size());
                                    int next = object.path("offset").asInt() + items.size();
                                    object.put("hasMore", next < object.path("total").asInt());
                                    if (next < object.path("total").asInt()) object.put("nextOffset", next);
                                }
                            }
                        }
                        payload = json.writeValueAsString(object);
                    }
                } catch (Exception ignored) { /* Non-JSON tool errors remain verbatim. */ }
                reduced.add(new ToolResponseMessage.ToolResponse(result.id(), result.name(), payload));
            }
            messages.set(i, ToolResponseMessage.builder().responses(reduced).build());
        }
        // Prefer retaining three recent turns; under hard pressure compact earlier turns
        // up to the current user message. The active assistant/tool exchange stays intact.
        int currentUser = 0;
        for (int i = 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) currentUser = i;
        }
        int preferredEnd = Math.max(1, currentUser - 6);
        if (AgentMessageBudget.estimate(messages, tools) >= trigger && preferredEnd > 1) {
            summarizeOrTrim(messages, preferredEnd, step, "history");
        }
        limit = Math.min(settings.contextBudget() - settings.outputBudget() - 600,
                RunContext.remaining() - settings.outputBudget() - 200);
        if (AgentMessageBudget.estimate(messages, tools) > limit) {
            currentUser = 0;
            for (int i = 1; i < messages.size(); i++) {
                if (messages.get(i) instanceof UserMessage) currentUser = i;
            }
            if (currentUser > 1) summarizeOrTrim(messages, currentUser, step, "pressure");
        }
        save("context-" + step, snapshot(messages));
        limit = Math.min(limit, RunContext.remaining() - settings.outputBudget() - 200);
        if (AgentMessageBudget.estimate(messages, tools) > limit)
            throw new ContextCapacityException(RunContext.remaining() - settings.outputBudget() - 200 < AgentMessageBudget.estimate(messages, tools)
                    ? "任务累计额度不足以继续生成；压缩不能恢复已消耗额度，原始记录已保留"
                    : "当前问题、工具定义及当前工具结果超过模型容量，历史已尝试压缩，原始记录已保留");
    }

    public static class ContextCapacityException extends IllegalArgumentException {
        public ContextCapacityException(String message) { super(message); }
    }

    /** One bounded overflow recovery, preserving the current user and all active tool pairs. */
    public boolean recover(List<Message> messages, int step) {
        int end = 0;
        for (int i = 1; i < messages.size(); i++) if (messages.get(i) instanceof UserMessage) end = i;
        if (end <= 1) return false;
        int before = AgentMessageBudget.estimate(messages, List.of());
        save("before-recovery-" + step, snapshot(messages));
        trimPrefix(messages, end);
        save("recovery-" + step, snapshot(messages));
        return AgentMessageBudget.estimate(messages, List.of()) < before;
    }

    private void summarizeOrTrim(List<Message> messages, int end, int step, String phase) {
        try {
            summarizePrefix(messages, end, step, phase);
        } catch (CancellationException e) {
            throw e;
        } catch (SummaryUnavailable e) {
            RunContext.check();
            trimPrefix(messages, end);
            save("summary-fallback-" + step + "-" + phase,
                    Map.of("reason", e.getMessage(), "coveredPrefixMessages", end - 1));
        }
    }

    private void trimPrefix(List<Message> messages, int end) {
        messages.subList(1, end).clear();
        messages.add(1, new UserMessage("[上下文提示：部分早期对话已移出本次输入，原文仍保存。不得猜测缺失事实；回答涉及这些事实时说明上下文不完整并请求澄清。]"));
    }

    private static class SummaryUnavailable extends RuntimeException {
        SummaryUnavailable(String reason) { super(reason); }
    }

    private void summarizePrefix(List<Message> messages, int end, int step, String phase) {
        // Only whole conversation turns are summarized; tool calls and results are never split.
        if (!(messages.get(end) instanceof UserMessage)) return;
        List<Message> older = new ArrayList<>(messages.subList(1, end));
        String source;
        try { source = json.writeValueAsString(snapshot(older)); }
        catch (Exception e) { throw new IllegalStateException("无法序列化历史", e); }
        List<Message> input = List.of(new SystemMessage(prompts.get("summary").text()), new UserMessage(source));
        int cost = AgentMessageBudget.estimate(input, List.of()) + 500;
        if (cost + 200 > settings.contextBudget())
            throw new SummaryUnavailable("待摘要历史超过单次压缩容量，原始记录已保留");
        int finalReserve = AgentMessageBudget.estimate(messages.subList(end, messages.size()), List.of())
                + AgentMessageBudget.estimate(List.of(messages.getFirst()), List.of()) + settings.outputBudget() + 2400;
        if ((long) cost + finalReserve > RunContext.remaining())
            throw new SummaryUnavailable("任务累计额度不足以调用摘要模型，原始记录已保留");
        String summary;
        try { summary = ai.complete("summary", input, 500).text(); }
        catch (CancellationException e) { throw e; }
        catch (RuntimeException e) {
            RunContext.check();
            throw new SummaryUnavailable("摘要调用失败：" + e.getClass().getSimpleName());
        }
        if (summary == null || summary.isBlank())
            throw new SummaryUnavailable("摘要模型返回空内容，原始上下文已保留");
        Message compact = new UserMessage("<conversation_summary>\n" + summary + "\n</conversation_summary>");
        if (AgentMessageBudget.estimate(List.of(compact), List.of()) >= AgentMessageBudget.estimate(older, List.of()))
            throw new SummaryUnavailable("摘要未缩短上下文，原始上下文已保留");
        RunContext.check();
        save("summary-" + step + "-" + phase, Map.of("step", step, "coveredPrefixMessages", end - 1, "content", summary));
        messages.subList(1, end).clear();
        messages.add(1, compact);
    }

    private List<Map<String, Object>> snapshot(List<Message> messages) {
        return messages.stream().map(message -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("role", message.getMessageType().getValue());
            value.put("content", message.getText() == null ? "" : message.getText());
            if (message instanceof AssistantMessage a) value.put("toolCalls", a.getToolCalls());
            if (message instanceof ToolResponseMessage t) value.put("toolResponses", t.getResponses());
            return value;
        }).toList();
    }
}
