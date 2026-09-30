package com.mindhaven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.AiGateway;
import com.mindhaven.integration.ai.PromptRepository;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.service.agent.AgentContext;
import com.mindhaven.service.ai.AiOperations;
import com.mindhaven.service.ai.RunContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentContextTest {
    Settings settings = mock(Settings.class);
    RecordManager records = mock(RecordManager.class);
    AiOperations ai = mock(AiOperations.class);
    PromptRepository prompts = mock(PromptRepository.class);
    AgentContext context() {
        when(settings.contextBudget()).thenReturn(4000);
        when(settings.outputBudget()).thenReturn(500);
        when(prompts.get("summary")).thenReturn(new PromptRepository.Template("summary", "summarize", "hash"));
        return new AgentContext(records, ai, prompts, settings, new ObjectMapper());
    }
    @Test
    void toolCompactionRetainsProtocolAndSavesFullSnapshot() throws Exception {
        var context = context();
        var call = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call", "function", "searchKnowledge", "{}"))).build();
        String payload = "{\"documents\":[{\"id\":\"a\",\"text\":\"kept\"},{\"id\":\"b\",\"text\":\"" + "x".repeat(3300) + "\"}]}";
        var messages = new ArrayList<Message>(List.of(new SystemMessage("system"), new UserMessage("question"), call,
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call", "searchKnowledge", payload))).build()));
        try (var run = RunContext.open("test", () -> false, 20000)) {
            context.prepare(messages, List.of(), 2);
        }
        assertThat(messages.get(2)).isSameAs(call);
        var result = ((ToolResponseMessage) messages.getLast()).getResponses().getFirst();
        assertThat(result.id()).isEqualTo("call");
        assertThat(result.responseData()).contains("kept", "contextCompacted").doesNotContain("xxx");
        verify(records).put(eq("agent-state"), eq("test:before-context-2"), argThat(value -> value.toString().contains("xxx")));
        verifyNoInteractions(ai);
    }
    @Test
    void oldHistorySummaryPreservesRecentThreeTurnsAndCurrentQuestion() throws Exception {
        var context = context();
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage("system"));
        for (int i=0;i<8;i++) {
            messages.add(new UserMessage("u" + i + "x".repeat(200)));
            messages.add(new AssistantMessage("a" + i + "y".repeat(200)));
        }
        messages.add(new UserMessage("current"));
        var recent = new ArrayList<>(messages.subList(messages.size()-7, messages.size()));
        when(ai.complete(eq("summary"), anyList(), anyInt())).thenReturn(new AiGateway.Result("old facts", 10, 10));
        // Summary input itself must fit the model window.
        when(settings.contextBudget()).thenReturn(6000);
        try (var run = RunContext.open("test", () -> false, 30000)) {
            context.prepare(messages, List.of(), 2);
        }
        // Trigger compression by lowering the limit if needed.
        if (messages.size() > 9) {
            when(settings.contextBudget()).thenReturn(4000);
            try (var run = RunContext.open("test", () -> false, 30000)) { context.prepare(messages, List.of(), 3); }
        }
        assertThat(messages.subList(messages.size()-7, messages.size())).containsExactlyElementsOf(recent);
        assertThat(messages.get(1).getText()).contains("old facts");
    }
    @Test
    void futureToolHeadroomDoesNotRejectAnAffordableCurrentCall() throws Exception {
        var context = context();
        var messages = new ArrayList<Message>(List.of(
                new SystemMessage("system"), new UserMessage("x".repeat(1800))));
        try (var run = RunContext.open("test", () -> false, 4000)) {
            assertThatCode(() -> context.prepare(messages, List.of(), 2, 2400))
                    .doesNotThrowAnyException();
        }
        verifyNoInteractions(ai);
    }

    @Test
    void actualRemainingBudgetIsStillEnforced() throws Exception {
        var context = context();
        var messages = new ArrayList<Message>(List.of(
                new SystemMessage("system"), new UserMessage("x".repeat(1800))));
        try (var run = RunContext.open("test", () -> false, 2000)) {
            assertThatThrownBy(() -> context.prepare(messages, List.of(), 2, 2400))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void hardPressureAutomaticallySummarizesShortHistoryAndKeepsActiveToolPair() throws Exception {
        var context = context();
        var call = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call", "function", "findCourses", "{}"))).build();
        var tool = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call", "findCourses", "{\"items\":[]}"))).build();
        var current = new UserMessage("current question");
        var messages = new ArrayList<Message>(List.of(new SystemMessage("s".repeat(800)),
                new UserMessage("old" + "x".repeat(1300)), new AssistantMessage("a".repeat(1100)), current, call, tool));
        when(ai.complete(eq("summary"), anyList(), anyInt()))
                .thenReturn(new AiGateway.Result("important earlier facts", 10, 10));
        try (var run = RunContext.open("test", () -> false, 20000)) {
            context.prepare(messages, List.of(), 2);
        }
        verify(ai).complete(eq("summary"), anyList(), anyInt());
        assertThat(messages).containsSubsequence(current, call);
        assertThat(messages.get(1).getText()).contains("important earlier facts");
        assertThat(((ToolResponseMessage) messages.getLast()).getResponses().getFirst().id()).isEqualTo("call");
    }

    @Test
    void summaryFailureKeepsCurrentQuestionAndMarksMissingHistory() throws Exception {
        var context = context();
        var current = new UserMessage("current");
        var messages = new ArrayList<Message>(List.of(new SystemMessage("s".repeat(800)),
                new UserMessage("x".repeat(1300)), new AssistantMessage("y".repeat(1100)), current));
        when(ai.complete(eq("summary"), anyList(), anyInt())).thenThrow(new IllegalStateException("provider unavailable"));
        try (var run = RunContext.open("test", () -> false, 20000)) {
            context.prepare(messages, List.of(), 2);
        }
        assertThat(messages.getLast()).isSameAs(current);
        assertThat(messages.get(1).getText()).contains("上下文不完整");
        verify(records).put(eq("agent-state"), contains("summary-fallback"), any());
    }

    @Test
    void recoveryMustReduceAndPreservesCurrentExchange() {
        var context = context();
        var current = new UserMessage("current");
        var messages = new ArrayList<Message>(List.of(new SystemMessage("system"),
                new UserMessage("x".repeat(1000)), new AssistantMessage("answer"), current));
        assertThat(context.recover(messages, 1)).isTrue();
        assertThat(messages.getLast()).isSameAs(current);
        assertThat(context.recover(messages, 1)).isFalse();
    }

    @Test
    void compactingPageNeverRewindsActualCursorOrReturnedCount() throws Exception {
        var call = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("page", "function", "findCourses", "{}"))).build();
        String payload = "{\"total\":103,\"returned\":30,\"nextOffset\":60,\"hasMore\":true,\"items\":[{\"id\":\"a\"},{\"id\":\"b\",\"text\":\"" + "x".repeat(4000) + "\"}]}";
        var messages = new ArrayList<Message>(List.of(new SystemMessage("s"), new UserMessage("前100门"), call,
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("page", "findCourses", payload))).build()));
        try (var run = RunContext.open("test", () -> false, 20000)) { context().prepare(messages, List.of(), 1); }
        var compact = new ObjectMapper().readTree(((ToolResponseMessage) messages.getLast()).getResponses().getFirst().responseData());
        assertThat(compact.path("nextOffset").asInt()).isEqualTo(60);
        assertThat(compact.path("returned").asInt()).isEqualTo(30);
        assertThat(compact.path("visibleItems").asInt()).isEqualTo(1);
        assertThat(messages.get(1).getText()).isEqualTo("前100门");
    }

}
