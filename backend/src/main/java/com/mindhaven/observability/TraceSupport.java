package com.mindhaven.observability;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

/**
 * Scope always restores thread-local context, including pooled worker threads.
 */
public final class TraceSupport implements AutoCloseable {
    private final Span span;
    private final Scope scope;
    private final String previousTrace, previousSpan;

    public TraceSupport(Span span) {
        this.span = span;
        previousTrace = MDC.get("traceId");
        previousSpan = MDC.get("spanId");
        scope = span.makeCurrent();
        MDC.put("traceId", span.getSpanContext().getTraceId());
        MDC.put("spanId", span.getSpanContext().getSpanId());
    }

    public void failed(Throwable failure) {
        span.setStatus(StatusCode.ERROR);
        span.setAttribute("error.type", failure.getClass().getSimpleName());
    }

    public void close() {
        try {
            scope.close();
            restore("traceId", previousTrace);
            restore("spanId", previousSpan);
        } finally {
            span.end();
        }
    }

    private void restore(String key, String value) {
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }
}
