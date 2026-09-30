package com.mindhaven;

import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.function.Consumer;

import org.springframework.ai.chat.messages.Message;

import java.util.concurrent.atomic.AtomicInteger;

import java.time.Duration;

import java.util.NoSuchElementException;

import com.mindhaven.common.error.HttpProblem;

import com.mindhaven.model.dto.ChatCommand;

import com.mindhaven.service.ai.ChatRunService;

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

import static org.awaitility.Awaitility.await;
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
    @Autowired ChatRunService runs;

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
            when(gateway.streamStep(anyList(), anyInt(), anyList(), any())).thenAnswer(invocation -> {
                Consumer<String> emit = invocation.getArgument(3);
                emit.accept("我先查一下课程。");
                return new AgentStep(AssistantMessage.builder().content("我先查一下课程。").toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-1", "function", "findCourses", "{\"query\":\"学习课程A\"}"))).build(), "tool_calls", 100, 10);
            });
            doAnswer(invocation -> {
                var response = new AgentStep(new AssistantMessage("可以看看学习课程A。"), "stop", 120, 20);
                Consumer<String> emit = invocation.getArgument(3);
                emit.accept(response.message().getText());
                return response;
            }).when(gateway).streamStep(argThat(ms -> ms.stream().anyMatch(m -> m instanceof ToolResponseMessage)), anyInt(), anyList(), any());
            var session = sessions.create();
            var events = new ArrayList<ChatEvent>();
            chat.turn(session.id(), "推荐学习课程", "全部", "v1", true, events::add);
            var saved = messages.list(session.id());
            assertThat(saved).hasSize(2);
            assertThat(saved).allMatch(m -> m.status().equals("complete"));
            assertThat(saved.getLast().execution()).extracting(ChatEvent.AgentStatus::phase)
                    .containsExactly("model", "draft", "tool", "model");
            assertThat(saved.getLast().execution().get(1).draft()).isEqualTo("我先查一下课程。");
            assertThat(saved.getLast().content()).doesNotContain("我先查一下");
            assertThat(saved.getLast().execution().get(2).toolName()).isEqualTo("findCourses");
            assertThat(saved.getLast().recommendations()).hasSize(1);
            assertThat(saved.getLast().recommendations().getFirst().id()).isEqualTo("published-a");
            assertThat(events).anyMatch(e -> e instanceof ChatEvent.Recommendations);
            assertThat(records.get("agent-state", "agent-fixture:result-call-1", Map.class)).isPresent();
            assertThat(records.get("agent-state", "agent-fixture:completed", Map.class)).isPresent();
            verify(gateway, times(2)).streamStep(anyList(), anyInt(), anyList(), any());
            verify(gateway, never()).stream(anyList(), anyInt(), any());
        }
    }
    @Test
    void continuationKeepsGoalBudgetAndDoesNotDuplicateChildRun() throws Exception {
        var account = auth.register("resume-" + UUID.randomUUID(), "Resume", "admin", "test-password-123");
        var other = auth.register("other-" + UUID.randomUUID(), "Other", "admin", "test-password-123");
        var count = new AtomicInteger();
        when(gateway.streamStep(anyList(), anyInt(), anyList(), any())).thenAnswer(invocation -> {
            int n = count.incrementAndGet();
            List<Message> input = invocation.getArgument(0);
            if (n == 2) {
                assertThat(input.toString()).contains("完整介绍学习方法", "第一部分");
            }
            String text = n == 1 ? "第一部分" : "第二部分，介绍完成";
            Consumer<String> emit = invocation.getArgument(3);
            emit.accept(text);
            return new AgentStep(new AssistantMessage(text), n == 1 ? "length" : "stop", 100, 20);
        });
        String original;
        try (var scope = TenantContext.open(account.identity())) {
            var session = sessions.create();
            original = runs.create(session.id(), new ChatCommand("完整介绍学习方法", "全部", "v1", false, UUID.randomUUID().toString())).id();
            await().atMost(Duration.ofSeconds(8)).until(() -> {
                try (var identity = TenantContext.open(account.identity())) {
                    return runs.get(original).status().terminal()
                            && records.get("run-continuations", original, ChatRunService.Continuation.class)
                            .map(c -> c.remaining() < 1500000).orElse(false);
                }
            });
            assertThat(messages.list(session.id()).getLast().status()).isEqualTo("partial");
            final String[] child = {null};
            await().atMost(Duration.ofSeconds(3)).until(() -> {
                try (var identity = TenantContext.open(account.identity())) {
                    try { child[0] = runs.continueRun(original).id(); return true; }
                    catch (HttpProblem e) { return false; }
                }
            });
            assertThat(runs.continueRun(original).id()).isEqualTo(child[0]);
            await().atMost(Duration.ofSeconds(8)).until(() -> {
                try (var identity = TenantContext.open(account.identity())) { return runs.get(child[0]).status().terminal(); }
            });
            assertThat(messages.list(session.id()).getLast().content()).contains("第二部分");
            var parentState = records.get("run-continuations", original, ChatRunService.Continuation.class).orElseThrow();
            var childState = records.get("run-continuations", child[0], ChatRunService.Continuation.class).orElseThrow();
            assertThat(childState.remaining()).isLessThanOrEqualTo(parentState.remaining());
            assertThat(childState.parentId()).isEqualTo(original);
        }
        try (var scope = TenantContext.open(other.identity())) {
            assertThatThrownBy(() -> runs.continueRun(original)).isInstanceOf(NoSuchElementException.class);
        }
        assertThat(count.get()).isEqualTo(2);
    }

}
