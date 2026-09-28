package com.mindhaven.infrastructure.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.Collection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class TraceConfiguration {
  @Bean(destroyMethod = "close")
  OpenTelemetrySdk telemetry(
      @Value("${mindhaven.tracing.otlp-endpoint:}") String endpoint,
      @Value("${mindhaven.tracing.sample-rate:1.0}") double rate) {
    var provider =
        SdkTracerProvider.builder()
            .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(rate)))
            .setResource(
                Resource.create(
                    Attributes.of(AttributeKey.stringKey("service.name"), "mindhaven-backend")))
            .addSpanProcessor(SimpleSpanProcessor.create(new SafeLogExporter()));
    if (!endpoint.isBlank())
      provider.addSpanProcessor(
          BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build())
              .build());
    return OpenTelemetrySdk.builder().setTracerProvider(provider.build()).build();
  }

  @Bean
  Tracer tracer(OpenTelemetrySdk sdk) {
    return sdk.getTracer("mindhaven");
  }

  /**
   * Only a fixed metadata allowlist is logged; never prompts, messages, credentials or exception
   * bodies.
   */
  static class SafeLogExporter implements SpanExporter {
    public CompletableResultCode export(Collection<SpanData> spans) {
      for (var span : spans)
        org.slf4j.LoggerFactory.getLogger("mindhaven.trace")
            .info(
                "traceId={} spanId={} parentSpanId={} operation={} durationMs={} status={}"
                    + " runId={}",
                span.getTraceId(),
                span.getSpanId(),
                span.getParentSpanId(),
                span.getName(),
                (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1000000,
                span.getStatus().getStatusCode(),
                span.getAttributes().get(AttributeKey.stringKey("run.id")));
      return CompletableResultCode.ofSuccess();
    }

    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
