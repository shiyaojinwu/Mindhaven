package com.mindhaven;

import com.mindhaven.integration.ai.AiGateway;
import com.mindhaven.manager.MessageManager;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.model.ai.AgentStep;
import com.mindhaven.model.chat.ChatEvent;
import com.mindhaven.model.course.Course;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.agent.AgentToolExecutor;
import com.mindhaven.service.ai.RunContext;
import com.mindhaven.service.auth.AuthService;
import com.mindhaven.service.chat.ChatService;
import com.mindhaven.service.chat.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "mindhaven.ai-mode=live",
        "mindhaven.vector-mode=local", "mindhaven.agent.enabled=true"})
class AgentIntegrationTest {
    @MockitoBean AiGateway gateway;
    @Autowired ChatService chat;
    @Autowired SessionService sessions;
    @Autowired AuthService auth;
    @Autowired RecordManager records;
    @Autowired MessageManager messages;
    @Autowired AgentToolExecutor tools;

    @Test
    void agentUsesTenantPublishedDataAndPersistsCardsWithCompletedTurn() throws Exception {
        var a = auth.register("agent-a-" + UUID.randomUUID(), "A", "admin", "test-password-123");
        var b = auth.register("agent-b-" + UUID.randomUUID(), "B", "admin", "test-password-123");
        try (var scope = TenantContext.open(b.identity())) {
            records.put("courses", "private-b", new Course("private-b", "私有课程B", "学习", 2, "other", "body"));
        }
        try (var scope = TenantContext.open(a.identity()); var run = RunContext.open("agent-fixture", () -> false, 40000)) {
            records.put("courses", "published-a", new Course("published-a", "学习课程A", "学习", 2, "intro", "body"));
            var result = tools.execute("findCourses", "{\"query\":\"课程\"}", "全部", "v1");
            assertThat(result.payload()).contains("published-a").doesNotContain("private-b");
            when(gateway.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(
                    new AgentStep(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-1", "function", "findCourses", "{\"query\":\"学习课程A\"}"))).build(), "tool_calls", 100, 10),
                    new AgentStep(new AssistantMessage("可以看看学习课程A。"), "stop", 120, 20));
            doAnswer(invocation -> {
                var response = new AgentStep(new AssistantMessage("可以看看学习课程A。"), "stop", 120, 20);
                java.util.function.Consumer<String> emit = invocation.getArgument(3);
                emit.accept(response.message().getText());
                return response;
            }).when(gateway).streamStep(argThat(ms -> ms.stream().anyMatch(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage)), anyInt(), anyList(), any());
            var session = sessions.create();
            var events = new ArrayList<ChatEvent>();
            chat.turn(session.id(), "推荐学习课程", "全部", "v1", true, events::add);
            var saved = messages.list(session.id());
            assertThat(saved).hasSize(2);
            assertThat(saved).allMatch(m -> m.status().equals("complete"));
            assertThat(saved.getLast().recommendations()).hasSize(1);
            assertThat(saved.getLast().recommendations().getFirst().id()).isEqualTo("published-a");
            assertThat(events).anyMatch(e -> e instanceof ChatEvent.Recommendations);
            assertThat(records.get("agent-state", "agent-fixture:result-call-1", Map.class)).isPresent();
            assertThat(records.get("agent-state", "agent-fixture:completed", Map.class)).isPresent();
            verify(gateway, times(2)).streamStep(anyList(), anyInt(), anyList(), any());
            verify(gateway, never()).stream(anyList(), anyInt(), any());
        }
    }
}
