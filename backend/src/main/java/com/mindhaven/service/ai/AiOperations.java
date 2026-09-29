package com.mindhaven.service.ai;

import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.*;
import com.mindhaven.integration.storage.*;
import com.mindhaven.integration.vector.*;
import com.mindhaven.manager.UsageManager;
import com.mindhaven.model.ai.AiUsage;
import com.mindhaven.model.ai.AgentStep;
import com.mindhaven.model.ai.AgentTool;
import com.mindhaven.service.agent.AgentMessageBudget;
import com.mindhaven.service.chat.ContextPlanner;
import io.opentelemetry.api.trace.Span;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/**
 * Shared model boundary for input budgets, cancellation and durable stage usage.
 */
@Service
public class AiOperations {
    private final AiGateway gateway;
    private final UsageManager usage;
    private final PromptRepository prompts;
    private final Settings settings;

    public AiOperations(AiGateway gateway, UsageManager usage, PromptRepository prompts, Settings settings) {
        this.gateway = gateway;
        this.usage = usage;
        this.prompts = prompts;
        this.settings = settings;
    }

    public AiGateway.Result complete(String purpose, List<Message> messages, int max) {
        return stream(purpose, messages, max, d -> {
        });
    }

    public AiGateway.Result stream(String purpose, List<Message> messages, int max, Consumer<String> emit) {
        RunContext.check();
        int input = messages.stream().mapToInt(m -> ContextPlanner.estimate(m.getText())).sum();
        if (input + max + 200 > settings.contextBudget())
            throw new IllegalArgumentException("输入超过模型预算，请缩短内容后重试");
        RunContext.reserve(input + max);
        var span = Span.current();
        span.setAttribute("ai.model", settings.aiMode().equals("demo") ? "deterministic-demo" : settings.chatModel());
        long start = System.nanoTime();
        span.setAttribute("ai.prompt_hash", prompts.get(purpose).hash());
        span.setAttribute("ai.input_tokens_estimated", input);
        span.setAttribute("ai.output_token_limit", max);
        boolean[] first = {true};
        StringBuilder partial = new StringBuilder();
        AiGateway.Result result = null;
        String status = "FAILED";
        try {
            result = gateway.stream(messages, max, delta -> {
                RunContext.check();
                if (first[0] && !delta.isEmpty()) {
                    first[0] = false;
                    span.setAttribute("ai.first_chunk_ms", (System.nanoTime() - start) / 1_000_000);
                    span.addEvent("model.first_chunk");
                }
                partial.append(delta);
                emit.accept(delta);
            });
            RunContext.check();
            RunContext.settle(input + max,
                    result.promptTokens() != null && result.promptTokens() >= 0 ? result.promptTokens() : input,
                    result.completionTokens() != null && result.completionTokens() >= 0 ? result.completionTokens() : ContextPlanner.estimate(partial.toString()));
            status = "COMPLETED";
            return result;
        } catch (CancellationException e) {
            status = "CANCELLED";
            throw e;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                boolean demo = settings.aiMode().equals("demo");
                Integer in = demo ? Integer.valueOf(0) : result == null ? null : result.promptTokens(), out = demo ? Integer.valueOf(0) : result == null ? null : result.completionTokens();
                String source = demo ? "demo" : in != null && out != null ? "provider" : "estimated";
                span.setAttribute("ai.usage_source", source);
                span.setAttribute("ai.status", interrupted ? "CANCELLED" : status);
                if (in != null) span.setAttribute("ai.input_tokens", in);
                if (out != null) span.setAttribute("ai.output_tokens", out);
                span.setAttribute("ai.output_tokens_estimated", demo ? 0 : ContextPlanner.estimate(partial.toString()));
                usage.save(new AiUsage(UUID.randomUUID().toString(), RunContext.id(), purpose, demo ? "deterministic-demo" : settings.chatModel(), prompts.get(purpose).hash(), interrupted ? "CANCELLED" : status, source, in, out, demo ? 0 : input, demo ? 0 : ContextPlanner.estimate(partial.toString()), (System.nanoTime() - start) / 1_000_000, Instant.now().toString()));
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    /** Each native tool decision is budgeted and accounted independently. */
    public AgentStep step(List<Message> messages, int max, List<AgentTool> tools) {
        return streamStep(messages, max, tools, ignored -> {});
    }

    public AgentStep streamStep(List<Message> messages, int max, List<AgentTool> tools, Consumer<String> emit) {
        int input = AgentMessageBudget.estimate(messages, tools);
        if (input + max + 200 > settings.contextBudget()) throw new IllegalArgumentException("Agent 上下文预算不足，请新建对话");
        RunContext.reserve(input + max);
        long started = System.nanoTime();
        AgentStep result = null;
        String status = "FAILED";
        try {
            result = gateway.streamStep(messages, max, tools, text -> { RunContext.check(); emit.accept(text); });
            RunContext.check();
            RunContext.settle(input + max,
                    result.promptTokens() != null && result.promptTokens() >= 0 ? result.promptTokens() : input,
                    result.completionTokens() != null && result.completionTokens() >= 0 ? result.completionTokens() : AgentMessageBudget.estimate(List.of(result.message()), List.of()));
            status = "COMPLETED";
            return result;
        } catch (CancellationException e) {
            status = "CANCELLED";
            throw e;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                Integer in = result == null ? null : result.promptTokens();
                Integer out = result == null ? null : result.completionTokens();
                int output = result == null ? 0 : AgentMessageBudget.estimate(List.of(result.message()), List.of());
                var span = Span.current();
                span.setAttribute("ai.input_tokens_estimated", input);
                span.setAttribute("ai.output_tokens_estimated", output);
                span.setAttribute("ai.status", interrupted ? "CANCELLED" : status);
                span.setAttribute("ai.usage_source", in != null && out != null ? "provider" : "estimated");
                if (in != null) span.setAttribute("ai.input_tokens", in);
                if (out != null) span.setAttribute("ai.output_tokens", out);
                usage.save(new AiUsage(UUID.randomUUID().toString(), RunContext.id(), "agent", settings.chatModel(),
                        prompts.get("agent").hash(), interrupted ? "CANCELLED" : status,
                        in != null && out != null ? "provider" : "estimated", in, out, input, output,
                        (System.nanoTime() - started) / 1_000_000, Instant.now().toString()));
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    public <T> T embedding(String purpose, String input, Supplier<T> call) {
        var span = Span.current();
        span.setAttribute("ai.model", settings.embeddingModel());
        span.setAttribute("ai.usage_source", "estimated");
        span.setAttribute("ai.input_tokens_estimated", ContextPlanner.estimate(input));
        RunContext.reserve(ContextPlanner.estimate(input));
        long start = System.nanoTime();
        String status = "FAILED";
        try {
            T value = call.get();
            RunContext.check();
            status = "COMPLETED";
            return value;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                usage.save(new AiUsage(UUID.randomUUID().toString(), RunContext.id(), purpose, settings.embeddingModel(), null, interrupted ? "CANCELLED" : status, "estimated", null, null, ContextPlanner.estimate(input), 0, (System.nanoTime() - start) / 1_000_000, Instant.now().toString()));
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    public List<AiUsage> usage(String runId) {
        return usage.recent(runId);
    }
}
