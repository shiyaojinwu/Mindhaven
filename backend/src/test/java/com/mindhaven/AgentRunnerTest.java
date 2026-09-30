package com.mindhaven;

import com.mindhaven.config.AgentSettings;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.ai.PromptRepository;
import com.mindhaven.model.ai.AgentStep;
import com.mindhaven.model.ai.AgentTool;
import com.mindhaven.model.chat.ChatEvent;
import com.mindhaven.security.LoginIdentity;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.agent.AgentRunner;
import com.mindhaven.service.agent.AgentContext;
import com.mindhaven.service.agent.ToolArgumentException;
import com.mindhaven.service.agent.AgentCompletion;
import com.mindhaven.service.agent.AgentToolExecutor;
import com.mindhaven.service.ai.AiOperations;
import com.mindhaven.service.ai.RunContext;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRunnerTest {
    AiOperations ai = mock(AiOperations.class);
    AgentToolExecutor tools = mock(AgentToolExecutor.class);
    Settings settings = mock(Settings.class);
    PromptRepository prompts = mock(PromptRepository.class);
    LoginIdentity identity = new LoginIdentity("tenant", "slug", "test", "user", "name", "MEMBER");
    List<Message> initial = List.of(new SystemMessage("base"), new UserMessage("课程"));
    AgentRunner runner;

    @BeforeEach
    void setup() {
        when(settings.contextBudget()).thenReturn(12000);
        when(settings.outputBudget()).thenReturn(1000);
        when(settings.chatModel()).thenReturn("test");
        when(prompts.get("agent")).thenReturn(new PromptRepository.Template("agent", "agent instructions", "hash"));
        when(tools.definitions()).thenReturn(List.of(new AgentTool("findCourses", "courses", "{}")));
        when(tools.execute(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentToolExecutor.Result("{\"items\":[]}", List.of(), List.of(), null));
        runner = new AgentRunner(ai, tools, new AgentSettings(true, 3, 2, 2, 512), settings, prompts, OpenTelemetry.noop().getTracer("test"), mock(AgentContext.class));
    }

    AgentStep call(String id) {
        return new AgentStep(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(id, "function", "findCourses", "{\"query\":\"\"}"))).build(), "tool_calls", 10, 5);
    }
    AgentStep answer(String reason) { return new AgentStep(new AssistantMessage("没有找到课程"), reason, 12, 6); }

    @Test
    void staleCitationIsReturnedToModelForCorrection() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(
                new AgentStep(new AssistantMessage("建议。[old-id]"), "stop", 10, 5),
                new AgentStep(new AssistantMessage("当前未核实相关资料，可以先聊聊你的感受。"), "stop", 10, 5));
        var events = new ArrayList<ChatEvent>();
        try (var scope = TenantContext.open(identity)) {
            var result = runner.run(initial, "全部", "v1", events::add);
            assertThat(result.answer().text()).doesNotContain("[old-id]");
            assertThat(result.incomplete()).isFalse();
        }
        assertThat(events).anyMatch(ChatEvent.AnswerReset.class::isInstance);
        verify(ai).streamStep(argThat(messages -> messages.stream()
                .anyMatch(m -> m instanceof SystemMessage && m.getText().contains("上一份回答引用校验未通过"))),
                anyInt(), anyList(), any());
    }

    @Test
    void repeatedInvalidCitationEndsWithConfirmedPartialResult() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(
                new AgentStep(new AssistantMessage("建议。[old-id]"), "stop", 10, 5));
        try (var scope = TenantContext.open(identity)) {
            var result = runner.run(initial, "全部", "v1", e -> {});
            assertThat(result.incomplete()).isTrue();
            assertThat(result.answer().text()).doesNotContain("[old-id]");
        }
        verify(ai, times(3)).streamStep(anyList(), anyInt(), anyList(), any());
    }

    @Test
    void toolResultIsAddedWithOriginalCallIdBeforeFinalAnswer() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(call("call-1"), answer("stop"));
        var events = new ArrayList<ChatEvent>();
        try (var scope = TenantContext.open(identity)) {
            var result = runner.run(initial, "全部", "v1", events::add);
            assertThat(result.answer().text()).isEqualTo("没有找到课程");
            assertThat(result.answer().promptTokens()).isEqualTo(22);
        }
        verify(tools).execute("findCourses", "{\"query\":\"\"}", "全部", "v1");
        verify(ai).streamStep(argThat(messages -> messages.size() == 4 && messages.getLast() instanceof ToolResponseMessage t
                && t.getResponses().getFirst().id().equals("call-1")), eq(1000), anyList(), any());
        assertThat(events).anyMatch(e -> e instanceof ChatEvent.AgentStatus status
                && "findCourses".equals(status.toolName()) && "call-1".equals(status.toolCallId())
                && "{\"query\":\"\"}".equals(status.arguments()));
    }

    @Test
    void repeatedCallDoesNotExecuteTwice() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(call("one"), call("two"), answer("stop"));
        try (var scope = TenantContext.open(identity)) {
            assertThat(runner.run(initial, "全部", "v1", e -> {}).answer().text()).isEqualTo("没有找到课程");
        }
        verify(tools, times(1)).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void truncatedResponseIsNotSuccessful() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(answer("length"));
        try (var scope = TenantContext.open(identity)) {
            var result = runner.run(initial, "全部", "v1", e -> {});
            assertThat(result.incomplete()).isTrue();
            assertThat(result.answer().text()).contains("尚未全部完成");
        }
        verify(tools, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void cancellationBeforeFirstStepDoesNotCallModel() throws Exception {
        try (var scope = TenantContext.open(identity); var run = RunContext.open("run", () -> true, 20000)) {
            assertThatThrownBy(() -> runner.run(initial, "全部", "v1", e -> { })).isInstanceOf(CancellationException.class);
        }
        verifyNoInteractions(ai);
    }

    @Test
    void lastStepDisablesToolsAndRejectsAnUnexpectedCall() {
        runner = new AgentRunner(ai, tools, new AgentSettings(true, 2, 2, 2, 512), settings, prompts, OpenTelemetry.noop().getTracer("test"), mock(AgentContext.class));
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(call("one"), call("two"), answer("stop"));
        try (var scope = TenantContext.open(identity)) {
            assertThatThrownBy(() -> runner.run(initial, "全部", "v1", e -> { })).hasMessageContaining("边界");
        }
        verify(ai).streamStep(anyList(), eq(1000), eq(List.of()), any());
        verify(tools, times(1)).execute(anyString(), anyString(), anyString(), anyString());
    }

    AgentStep batch(String... ids) {
        var calls = new ArrayList<AssistantMessage.ToolCall>();
        for (int i = 0; i < ids.length; i++) {
            calls.add(new AssistantMessage.ToolCall(ids[i], "function", "findCourses",
                    "{\"query\":\"topic-" + i + "\"}"));
        }
        return new AgentStep(AssistantMessage.builder().content("").toolCalls(calls).build(), "tool_calls", 10, 5);
    }

    @Test
    void multipleCallsReturnOneAssistantMessageAndAllMatchingResults() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(batch("one", "two"), answer("stop"));
        try (var scope = TenantContext.open(identity)) {
            runner.run(initial, "全部", "v1", e -> { });
        }
        var order = inOrder(tools);
        order.verify(tools).execute("findCourses", "{\"query\":\"topic-0\"}", "全部", "v1");
        order.verify(tools).execute("findCourses", "{\"query\":\"topic-1\"}", "全部", "v1");
        verify(ai).streamStep(argThat(messages -> messages.size() == 4
                && messages.get(2) instanceof AssistantMessage a && a.getToolCalls().size() == 2
                && messages.getLast() instanceof ToolResponseMessage t
                && t.getResponses().stream().map(ToolResponseMessage.ToolResponse::id).toList().equals(List.of("one", "two"))),
                eq(1000), eq(List.of()), any());
    }

    @Test
    void duplicateIdInBatchRejectsBeforeAnyToolExecutes() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(batch("same", "same"));
        assertBatchRejected("标识");
    }

    @Test
    void duplicateArgumentsInBatchReuseResultWithoutRejectingBatch() {
        var duplicate = new AgentStep(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("one", "function", "findCourses", "{}"),
                new AssistantMessage.ToolCall("two", "function", "findCourses", "{}"))).build(), "tool_calls", 10, 5);
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(duplicate, answer("stop"));
        try (var scope = TenantContext.open(identity)) { runner.run(initial, "全部", "v1", e -> {}); }
        verify(tools, times(1)).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void batchExceedingTotalLimitRejectsBeforeAnyToolExecutes() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(batch("one", "two", "three"), answer("stop"));
        assertLimitFeedback(3);
    }

    @Test
    void batchExceedingSearchLimitRejectsBeforeAnyToolExecutes() {
        runner = new AgentRunner(ai, tools, new AgentSettings(true, 3, 2, 1, 512), settings, prompts, OpenTelemetry.noop().getTracer("test"), mock(AgentContext.class));
        var response = new AgentStep(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("one", "function", "searchKnowledge", "{\"query\":\"a\"}"),
                new AssistantMessage.ToolCall("two", "function", "searchKnowledge", "{\"query\":\"b\"}"))).build(), "tool_calls", 10, 5);
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(response, answer("stop"));
        assertLimitFeedback(2);
    }

    @Test
    void actualSmallResultsDoNotFailWorstCaseBatchEstimate() {
        when(settings.contextBudget()).thenReturn(2900);
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(batch("one", "two"));
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(batch("one", "two"), answer("stop"));
        try (var scope = TenantContext.open(identity)) {
            runner.run(initial, "全部", "v1", e -> {});
        }
        verify(tools, times(2)).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void cancellationBetweenToolsStopsRemainingBatch() throws Exception {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(batch("one", "two"));
        when(tools.execute(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            cancelled.set(true);
            return new AgentToolExecutor.Result("{}", List.of(), List.of(), null);
        });
        try (var scope = TenantContext.open(identity); var run = RunContext.open("run", cancelled::get, 24000)) {
            assertThatThrownBy(() -> runner.run(initial, "全部", "v1", e -> { })).isInstanceOf(CancellationException.class);
        }
        verify(tools, times(1)).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void plainPromiseMustContinueToToolsAndExplicitFinish() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(
                new AgentStep(new AssistantMessage("稍等一下"), "stop", 1, 1), call("lookup"), answer("stop"));
        try (var scope = TenantContext.open(identity)) {
            assertThat(runner.run(initial, "全部", "v1", e -> {}).answer().text()).isEqualTo("没有找到课程");
        }
        verify(tools).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void completionRejectsPromiseButAllowsHistoryBasedAnswer() {
        assertThatThrownBy(() -> AgentCompletion.answer("稍等一下")).hasMessageContaining("承诺");
        assertThat(AgentCompletion.answer("根据刚才的课程介绍，可以先看沟通练习。"))
                .contains("沟通练习");
    }

    @Test
    void discussingPreviousCoursesDoesNotRequireFreshToolCallOrReset() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(answer("stop"));
        var events = new ArrayList<ChatEvent>();
        try (var scope = TenantContext.open(identity)) {
            runner.run(List.of(new SystemMessage("base"), new AssistantMessage("之前找到课程A和B"),
                    new UserMessage("刚才推荐的课程怎么选？")), "全部", "v1", events::add);
        }
        verify(tools, never()).execute(anyString(), anyString(), anyString(), anyString());
        assertThat(events).noneMatch(e -> e instanceof ChatEvent.AnswerReset);
    }

    @Test
    void greetingCanFinishWithoutReadTools() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(answer("stop"));
        try (var scope = TenantContext.open(identity)) {
            runner.run(List.of(new SystemMessage("base"), new UserMessage("你好")), "全部", "v1", e -> {});
        }
        verify(tools, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void repeatedPlainTextExhaustsStepsWithoutSuccessfulCompletion() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(new AgentStep(new AssistantMessage("稍等"), "stop", 1, 1));
        try (var scope = TenantContext.open(identity)) {
            assertThat(runner.run(initial, "全部", "v1", e -> {}).answer().text()).contains("未能完成全部请求");
        }
        verify(ai, times(3)).streamStep(anyList(), anyInt(), anyList(), any());
    }

    @Test
    void invalidFinishIsReturnedToModelForCorrection() {
        var invalid = new AgentStep(new AssistantMessage("稍等一下"), "stop", 1, 1);
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(invalid, call("lookup"), answer("stop"));
        try (var scope = TenantContext.open(identity)) {
            assertThat(runner.run(initial, "全部", "v1", e -> {}).answer().text()).isEqualTo("没有找到课程");
        }
    }

    @Test
    void invalidArgumentsReturnToolFeedbackAndModelCanCorrect() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(batch("bad"), call("corrected"), answer("stop"));
        when(tools.execute(eq("findCourses"), contains("topic-0"), anyString(), anyString()))
                .thenThrow(new ToolArgumentException("缺少 query"));
        try (var scope = TenantContext.open(identity)) {
            assertThat(runner.run(initial, "全部", "v1", e -> {}).answer().text()).isEqualTo("没有找到课程");
        }
        verify(ai).streamStep(argThat(ms -> ms.size() == 4 && ms.getLast() instanceof ToolResponseMessage t
                && t.getResponses().getFirst().id().equals("bad")
                && t.getResponses().getFirst().responseData().contains("INVALID_ARGUMENTS")), anyInt(), anyList(), any());
        verify(tools, times(2)).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void executionFailureIsNotConvertedToArgumentRetry() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(call("fail"));
        when(tools.execute(anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("database failed"));
        try (var scope = TenantContext.open(identity)) {
            assertThatThrownBy(() -> runner.run(initial, "全部", "v1", e -> {})).hasMessage("database failed");
        }
        verify(ai, times(1)).streamStep(anyList(), anyInt(), anyList(), any());
    }

    private void assertBatchRejected(String message) {
        try (var scope = TenantContext.open(identity)) {
            assertThatThrownBy(() -> runner.run(initial, "全部", "v1", e -> { })).hasMessageContaining(message);
        }
        verify(tools, never()).execute(anyString(), anyString(), anyString(), anyString());
    }
    @Test
    void lowBudgetEntersExplicitClosingWithNoTools() throws Exception {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(answer("stop"));
        var events = new ArrayList<ChatEvent>();
        try (var scope = TenantContext.open(identity); var run = RunContext.open("run", () -> false, 2000)) {
            runner.run(initial, "全部", "v1", events::add);
        }
        verify(ai).streamStep(anyList(), anyInt(), eq(List.of()), any());
        assertThat(events).anyMatch(e -> e instanceof ChatEvent.AgentStatus status && "finalizing".equals(status.phase()));
        verify(tools, never()).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void irreducibleProviderOverflowReturnsExplicitPartialInsteadOfRepeatingRequest() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any()))
                .thenThrow(new IllegalArgumentException("context_length_exceeded"));
        var events = new ArrayList<ChatEvent>();
        try (var scope = TenantContext.open(identity)) {
            var result = runner.run(initial, "全部", "v1", events::add);
            assertThat(result.answer().text()).contains("未能完成全部请求", "没有可展示的已确认资源");
        }
        verify(ai, times(1)).streamStep(anyList(), anyInt(), anyList(), any());
        assertThat(events).anyMatch(e -> e instanceof ChatEvent.AgentStatus status && "partial".equals(status.phase()));
    }

    @Test
    void providerOverflowRetriesOnceAfterRecovery() {
        var context = mock(AgentContext.class);
        when(context.recover(anyList(), anyInt())).thenReturn(true);
        runner = new AgentRunner(ai, tools, new AgentSettings(true, 3, 2, 2, 512), settings,
                prompts, OpenTelemetry.noop().getTracer("test"), context);
        when(ai.streamStep(anyList(), anyInt(), anyList(), any()))
                .thenThrow(new IllegalArgumentException("context_length_exceeded"))
                .thenReturn(answer("stop"));
        try (var scope = TenantContext.open(identity)) {
            assertThat(runner.run(initial, "全部", "v1", e -> {}).answer().text()).isEqualTo("没有找到课程");
        }
        verify(context).recover(anyList(), eq(1));
        verify(ai, times(2)).streamStep(anyList(), anyInt(), anyList(), any());
    }

    private void assertLimitFeedback(int count) {
        try (var scope = TenantContext.open(identity)) {
            runner.run(initial, "全部", "v1", e -> {});
        }
        verify(tools, never()).execute(anyString(), anyString(), anyString(), anyString());
        verify(ai).streamStep(argThat(messages -> messages.getLast() instanceof ToolResponseMessage t
                && t.getResponses().size() == count
                && t.getResponses().stream().allMatch(r -> r.responseData().contains("TOOL_CALL_LIMIT"))),
                anyInt(), eq(List.of()), any());
    }

    @Test
    void temporaryToolFailureReturnsMatchingResponseAndAllowsRetry() {
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenReturn(call("one"), call("two"), answer("stop"));
        when(tools.execute(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("private provider detail"))
                .thenReturn(new AgentToolExecutor.Result("{\"items\":[]}", List.of(), List.of(), null));
        try (var scope = TenantContext.open(identity)) { runner.run(initial, "全部", "v1", e -> {}); }
        verify(tools, times(2)).execute(anyString(), anyString(), anyString(), anyString());
        verify(ai).streamStep(argThat(messages -> messages.size() == 4 && messages.getLast() instanceof ToolResponseMessage t
                && t.getResponses().getFirst().id().equals("one")
                && t.getResponses().getFirst().responseData().contains("TOOL_UNAVAILABLE")
                && !t.getResponses().getFirst().responseData().contains("private provider detail")), anyInt(), anyList(), any());
    }

    @Test
    void completedArticleContainingWaitIsNotResetOrRetried() {
        String article = "好的，请稍等。\n演讲稿：各位同学，大家好。这里是已经写出的正文。谢谢大家。";
        when(ai.streamStep(anyList(), anyInt(), anyList(), any()))
                .thenReturn(new AgentStep(new AssistantMessage(article), "stop", 10, 20));
        var events = new ArrayList<ChatEvent>();
        try (var scope = TenantContext.open(identity)) {
            assertThat(runner.run(initial, "全部", "v1", events::add).answer().text()).isEqualTo(article);
        }
        assertThat(events).noneMatch(e -> e instanceof ChatEvent.AnswerReset);
        verify(ai, times(1)).streamStep(anyList(), anyInt(), anyList(), any());
    }

    @Test
    void punctuationCannotPassCompletionButQuotedWaitingCan() {
        for (String value : List.of("。", "...", "！\n？", " "))
            assertThatThrownBy(() -> AgentCompletion.answer(value)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AgentCompletion.answer("你可以说：请稍等，我需要整理一下想法。"))
                .contains("整理一下想法");
        assertThat(AgentCompletion.answer("你好！")).isEqualTo("你好！");
    }

    @Test
    void defaultStyleUnlimitedToolsCanFetchMoreThanThreePages() {
        runner = new AgentRunner(ai, tools, new AgentSettings(true, 64, 0, 0, 512), settings, prompts,
                OpenTelemetry.noop().getTracer("test"), mock(AgentContext.class));
        var steps = new ArrayList<AgentStep>();
        for (int i = 0; i < 5; i++) {
            String args = "{\"offset\":" + (i * 30) + "}";
            steps.add(new AgentStep(AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("page-" + i, "function", "findCourses", args))).build(), "tool_calls", 1, 1));
            when(tools.execute(eq("findCourses"), eq(args), anyString(), anyString())).thenReturn(
                    new AgentToolExecutor.Result("{\"items\":[{\"id\":\"page-" + i + "\"}],\"nextOffset\":" + ((i + 1) * 30) + "}", List.of(), List.of(), null));
        }
        steps.add(answer("stop"));
        var iterator = steps.iterator();
        when(ai.streamStep(anyList(), anyInt(), anyList(), any())).thenAnswer(call -> iterator.next());
        try (var scope = TenantContext.open(identity)) {
            assertThat(runner.run(initial, "全部", "v1", e -> {}).incomplete()).isFalse();
        }
        verify(tools, times(5)).execute(anyString(), anyString(), anyString(), anyString());
    }

}
