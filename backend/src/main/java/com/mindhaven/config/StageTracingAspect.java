package com.mindhaven.config;

import com.mindhaven.application.chat.ContextPlanner;
import com.mindhaven.common.observability.TraceSupport;
import com.mindhaven.domain.model.Models.Summary;
import com.mindhaven.domain.model.RetrievalResult;
import io.opentelemetry.api.trace.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class StageTracingAspect {
  private final Tracer tracer;

  public StageTracingAspect(Tracer tracer) {
    this.tracer = tracer;
  }

  @Around(
      "execution(* com.mindhaven.application.ai.AiOperations.stream(..)) || execution(*"
          + " com.mindhaven.application.ai.AiOperations.complete(..)) || execution(*"
          + " com.mindhaven.application.ai.AiOperations.embedding(..)) || execution(*"
          + " com.mindhaven.application.knowledge.KnowledgeService.retrieve(..)) || execution(*"
          + " com.mindhaven.application.chat.ContextPlanner.plan(..))")
  public Object stage(ProceedingJoinPoint point) throws Throwable {
    String name =
        point.getSignature().getDeclaringType().getSimpleName()
            + "."
            + point.getSignature().getName();
    var span = tracer.spanBuilder(name).startSpan();
    if (point.getSignature().getDeclaringType().getSimpleName().equals("AiOperations")) {
      String purpose = String.valueOf(point.getArgs()[0]);
      if (java.util.Set.of(
              "answer", "rewrite", "summary", "report", "query-embedding", "index-embedding")
          .contains(purpose)) span.setAttribute("ai.purpose", purpose);
    }
    span.addEvent("stage.started");
    if (point.getSignature().getDeclaringType().getSimpleName().equals("ContextPlanner")) {
      var args = point.getArgs();
      span.setAttribute("context.history_messages_available", ((java.util.List<?>) args[0]).size());
      span.setAttribute(
          "context.summary_version", args[1] instanceof Summary summary ? summary.version() : 0);
      Settings settings = (Settings) args[4];
      span.setAttribute("context.budget", settings.contextBudget());
      span.setAttribute("context.output_reserve", settings.outputBudget());
    }
    try (var scope = new TraceSupport(span)) {
      try {
        Object result = point.proceed();
        if (result instanceof RetrievalResult retrieval) {
          span.setAttribute("retrieval.mode", retrieval.mode());
          span.setAttribute("retrieval.result_count", retrieval.citations().size());
          span.setAttribute("retrieval.match_count", retrieval.matches().size());
          if (retrieval.configuration() != null) {
            span.setAttribute(
                "retrieval.candidate_limit", retrieval.configuration().candidateLimit());
            span.setAttribute("retrieval.rrf_k", retrieval.configuration().rrfK());
          }
        }
        if (result instanceof ContextPlanner.Plan plan) {
          span.setAttribute("context.estimated_tokens", plan.estimate());
          span.setAttribute("context.message_count", plan.messages().size());
          span.setAttribute("context.citation_count", plan.citations().size());
          span.setAttribute(
              "context.omitted_citation_count",
              ((java.util.List<?>) point.getArgs()[3]).size() - plan.citations().size());
        }
        span.addEvent("stage.completed");
        return result;
      } catch (Throwable e) {
        span.addEvent("stage.failed");
        scope.failed(e);
        throw e;
      }
    }
  }
}
