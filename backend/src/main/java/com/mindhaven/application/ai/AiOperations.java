package com.mindhaven.application.ai;

import com.mindhaven.application.chat.ContextPlanner;
import com.mindhaven.config.Settings;
import com.mindhaven.domain.model.AiUsage;
import com.mindhaven.domain.port.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

/** Shared model boundary for input budgets, cancellation and durable stage usage. */
@Service
public class AiOperations {
  private final AiGateway gateway;
  private final UsageRepository usage;
  private final PromptRepository prompts;
  private final Settings settings;

  public AiOperations(
      AiGateway gateway, UsageRepository usage, PromptRepository prompts, Settings settings) {
    this.gateway = gateway;
    this.usage = usage;
    this.prompts = prompts;
    this.settings = settings;
  }

  public AiGateway.Result complete(String purpose, List<Message> messages, int max) {
    return stream(purpose, messages, max, d -> {});
  }

  public AiGateway.Result stream(
      String purpose, List<Message> messages, int max, Consumer<String> emit) {
    RunContext.check();
    int input = messages.stream().mapToInt(m -> ContextPlanner.estimate(m.getText())).sum();
    if (input + max + 200 > settings.contextBudget())
      throw new IllegalArgumentException("输入超过模型预算，请缩短内容后重试");
    RunContext.reserve(input + max);
    var span = io.opentelemetry.api.trace.Span.current();
    span.setAttribute(
        "ai.model", settings.aiMode().equals("demo") ? "deterministic-demo" : settings.chatModel());
    long start = System.nanoTime();
    span.setAttribute("ai.prompt_hash", prompts.get(purpose).hash());
    span.setAttribute("ai.input_tokens_estimated", input);
    span.setAttribute("ai.output_token_limit", max);
    boolean[] first = {true};
    StringBuilder partial = new StringBuilder();
    AiGateway.Result result = null;
    String status = "FAILED";
    try {
      result =
          gateway.stream(
              messages,
              max,
              delta -> {
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
      status = "COMPLETED";
      return result;
    } catch (CancellationException e) {
      status = "CANCELLED";
      throw e;
    } finally {
      boolean interrupted = Thread.interrupted();
      try {
        boolean demo = settings.aiMode().equals("demo");
        Integer in = demo ? Integer.valueOf(0) : result == null ? null : result.promptTokens(),
            out = demo ? Integer.valueOf(0) : result == null ? null : result.completionTokens();
        String source = demo ? "demo" : in != null && out != null ? "provider" : "estimated";
        span.setAttribute("ai.usage_source", source);
        span.setAttribute("ai.status", interrupted ? "CANCELLED" : status);
        if (in != null) span.setAttribute("ai.input_tokens", in);
        if (out != null) span.setAttribute("ai.output_tokens", out);
        span.setAttribute(
            "ai.output_tokens_estimated", demo ? 0 : ContextPlanner.estimate(partial.toString()));
        usage.save(
            new AiUsage(
                UUID.randomUUID().toString(),
                RunContext.id(),
                purpose,
                demo ? "deterministic-demo" : settings.chatModel(),
                prompts.get(purpose).hash(),
                interrupted ? "CANCELLED" : status,
                source,
                in,
                out,
                demo ? 0 : input,
                demo ? 0 : ContextPlanner.estimate(partial.toString()),
                (System.nanoTime() - start) / 1_000_000,
                Instant.now().toString()));
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  public <T> T embedding(String purpose, String input, Supplier<T> call) {
    var span = io.opentelemetry.api.trace.Span.current();
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
        usage.save(
            new AiUsage(
                UUID.randomUUID().toString(),
                RunContext.id(),
                purpose,
                settings.embeddingModel(),
                null,
                interrupted ? "CANCELLED" : status,
                "estimated",
                null,
                null,
                ContextPlanner.estimate(input),
                0,
                (System.nanoTime() - start) / 1_000_000,
                Instant.now().toString()));
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  public List<AiUsage> usage(String runId) {
    return usage.recent(runId);
  }
}
