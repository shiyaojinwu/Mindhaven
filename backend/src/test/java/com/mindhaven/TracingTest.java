package com.mindhaven;

import com.mindhaven.service.chat.SessionService;
import com.mindhaven.model.chat.ChatEvent;

import com.mindhaven.model.dto.ChatCommand;
import com.mindhaven.observability.TraceSupport;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.ChatRunService;
import com.mindhaven.service.auth.AuthService;
import com.mindhaven.service.chat.ChatService;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "mindhaven.ai-mode=demo", "mindhaven.vector-mode=local"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class TracingTest {
    @Autowired
    Tracer tracer;
    @Autowired
    ChatRunService runs;
    @Autowired
    ChatService chat;
    @Autowired
    SessionService sessionService;
    @Autowired
    AuthService auth;
    @Autowired
    MockMvc mvc;

    @Test
    void httpReturnsServerGeneratedTraceAndCleansThreadContext() throws Exception {
        var response = mvc.perform(get("/api/health").header("X-Trace-Id", "untrusted")).andReturn().getResponse();
        assertThat(response.getHeader("X-Trace-Id")).matches("[a-f0-9]{32}");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void asyncRunAndStagesShareParentTraceWithoutLeakingContent(CapturedOutput output) {
        var login = auth.register("trace-" + UUID.randomUUID(), "Tracing test", "admin", "password-test-123");
        var parent = tracer.spanBuilder("test.parent").startSpan();
        String traceId = parent.getSpanContext().getTraceId();
        try (var identity = TenantContext.open(login.identity()); var scope = new TraceSupport(parent)) {
            var run = runs.create(sessionService.create().id(), new ChatCommand("你好", "全部", "v1", true, UUID.randomUUID().toString()));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut()).contains("operation=ai.run").contains("runId=" + run.id()));
            assertThat(output.getOut().lines().filter(s -> s.contains("operation=ai.run") && s.contains("runId=" + run.id())).findFirst().orElseThrow()).contains("traceId=" + traceId).contains("parentSpanId=" + parent.getSpanContext().getSpanId());
            assertThat(output.getOut().lines().filter(s -> s.contains("operation=AiOperations.stream") && s.contains("traceId=" + traceId)).count()).isPositive();
            assertThat(MDC.get("spanId")).isEqualTo(parent.getSpanContext().getSpanId());
        }
        assertThat(MDC.get("traceId")).isNull();
    }
}
