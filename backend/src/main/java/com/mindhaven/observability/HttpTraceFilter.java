package com.mindhaven.observability;

import com.mindhaven.observability.TraceSupport;
import io.opentelemetry.api.trace.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(-100)
public class HttpTraceFilter extends OncePerRequestFilter {
    private final Tracer tracer;

    public HttpTraceFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        // Treat browser input as untrusted: create our own root instead of accepting arbitrary trace
        // ids.
        var span = tracer.spanBuilder("http.request").setNoParent().setSpanKind(SpanKind.SERVER).setAttribute("http.request.method", request.getMethod()).startSpan();
        response.setHeader("X-Trace-Id", span.getSpanContext().getTraceId());
        try (var scope = new TraceSupport(span)) {
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException | RuntimeException e) {
                scope.failed(e);
                throw e;
            } finally {
                span.setAttribute("http.response.status_code", response.getStatus());
                if (response.getStatus() >= 500) span.setStatus(StatusCode.ERROR);
            }
        }
    }
}
