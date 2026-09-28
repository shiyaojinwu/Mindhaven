package com.mindhaven;

import com.mindhaven.service.chat.SessionService;
import com.mindhaven.model.chat.ChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.integration.ai.*;
import com.mindhaven.integration.storage.*;
import com.mindhaven.integration.vector.*;
import com.mindhaven.manager.RunManager;
import com.mindhaven.manager.UsageManager;
import com.mindhaven.model.chat.AiRun;
import com.mindhaven.model.chat.Session;
import com.mindhaven.model.dto.AnswerInput;
import com.mindhaven.model.dto.ChatCommand;
import com.mindhaven.model.dto.SurveyDraftInput;
import com.mindhaven.model.dto.SurveySubmission;
import com.mindhaven.model.questionnaire.Question;
import com.mindhaven.security.LoginIdentity;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.*;
import com.mindhaven.service.auth.AuthService;
import com.mindhaven.service.chat.ChatService;
import com.mindhaven.service.questionnaire.QuestionnaireService;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "mindhaven.ai-mode=demo", "mindhaven.vector-mode=local"})
class RuntimeTest {
    @Autowired
    AuthService auth;
    @Autowired
    ChatService chat;
    @Autowired
    SessionService sessionService;
    @Autowired
    ChatRunService runtime;
    @Autowired
    RunManager runs;
    @Autowired
    UsageManager usage;
    @Autowired
    QuestionnaireService surveys;
    @Autowired
    ObjectMapper json;
    TenantContext.Scope scope;

    @BeforeEach
    void identity() {
        var login = auth.register("r-" + UUID.randomUUID(), "Runtime test", "admin", "password-test-123");
        scope = TenantContext.open(login.identity());
    }

    @AfterEach
    void close() {
        scope.close();
    }

    ChatCommand command(String message, String key) {
        return new ChatCommand(message, "全部", "v1", true, key);
    }

    void completed(String id) {
        await().pollInSameThread().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runs.get(id).status()).isEqualTo(AiRun.Status.COMPLETED));
    }

    @Test
    void repeatedRequestAndEventReplayDoNotRepeatModelWork() {
        var session = sessionService.create();
        var input = command("学习压力有点大", UUID.randomUUID().toString());
        var first = runtime.create(session.id(), input);
        assertThat(runtime.create(session.id(), input).id()).isEqualTo(first.id());
        completed(first.id());
        var events = runtime.events(first.id(), 0);
        assertThat(events).extracting(AiRun.Event::name).contains("sources", "delta", "done");
        assertThat(runtime.create(session.id(), input).id()).isEqualTo(first.id());
        assertThat(runtime.events(first.id(), events.getFirst().seq())).isEqualTo(events.subList(1, events.size()));
        assertThat(sessionService.history(session.id())).hasSize(2);
        assertThat(usage.recent(first.id())).hasSize(1).allMatch(u -> u.source().equals("demo") && u.inputTokens() == 0 && u.promptHash().length() == 64);
        assertThatThrownBy(() -> runtime.create(session.id(), command("另一条问题", input.requestId()))).isInstanceOf(HttpProblem.class);
    }

    @Test
    void casualMessagesUseAnswerGatewayWithoutRetrievalOrRewrite() {
        for (String text : List.of("吃饭", "你好")) {
            var session = sessionService.create();
            var run = runtime.create(session.id(), command(text, UUID.randomUUID().toString()));
            completed(run.id());
            assertThat(usage.recent(run.id())).hasSize(1).allMatch(u -> u.purpose().equals("answer") && u.status().equals("COMPLETED"));
            assertThat(sessionService.history(session.id()).getLast().citations()).isEmpty();
            assertThat(runtime.events(run.id(), 0)).extracting(AiRun.Event::name).contains("delta", "done");
        }
    }

    @Test
    void runEventsCancellationAndUsageStayOwned() {
        var s = sessionService.create();
        var r = runtime.create(s.id(), command("我想聊聊考试", "one"));
        completed(r.id());
        var original = TenantContext.require();
        var other = new LoginIdentity(original.tenantId(), original.tenantSlug(), original.tenantName(), "other-member", "member", "MEMBER");
        try (var ignored = TenantContext.open(other)) {
            assertThatThrownBy(() -> runtime.get(r.id())).isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> runtime.events(r.id(), 0)).isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> runtime.cancel(r.id())).isInstanceOf(NoSuchElementException.class);
            assertThat(usage.recent(r.id())).isEmpty();
        }
    }

    @Test
    void cancellationKeepsPartialEventsAndBlocksLateCompletion() throws Exception {
        var fake = mock(ChatService.class);
        var fakeSessions = mock(SessionService.class);
        when(fakeSessions.session(anyString())).thenReturn(new Session("test", "test", "now"));
        var emitted = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        doAnswer(call -> {
            Consumer<ChatEvent> emit = call.getArgument(5);
            emit.accept(new ChatEvent.Delta("已生成的部分"));
            emitted.countDown();
            try {
                new CountDownLatch(1).await();
            } finally {
                stopped.countDown();
            }
            return null;
        }).when(fake).turn(anyString(), anyString(), anyString(), anyString(), anyBoolean(), any(), any());
        var isolated = new ChatRunService(OpenTelemetry.noop().getTracer("test"), fake, fakeSessions, runs, json, 30, 24000);
        try {
            var run = isolated.create("test", command("测试取消", "cancel"));
            assertThat(emitted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> isolated.create("other", command("重复任务", "other"))).isInstanceOf(HttpProblem.class);
            assertThat(isolated.cancel(run.id()).status()).isEqualTo(AiRun.Status.CANCELLED);
            assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(runs.events(run.id(), 0)).extracting(AiRun.Event::name).containsExactly("delta", "error");
            assertThatThrownBy(() -> runs.append(run.id(), "delta", Map.of("text", "late"))).isInstanceOf(CancellationException.class);
            assertThat(isolated.create("test", command("测试取消", "cancel")).id()).isEqualTo(run.id());
            verify(fake, times(1)).turn(anyString(), anyString(), anyString(), anyString(), anyBoolean(), any(), any());
        } finally {
            isolated.close();
        }
    }

    @Test
    void startupMarksAbandonedRunsWithoutRepeatingThem() {
        var s = sessionService.create();
        var r = runs.create(s.id(), "abandoned", "hash", "text").run();
        runs.start(r.id());
        runs.append(r.id(), "delta", Map.of("text", "partial"));
        runs.recoverInterrupted();
        assertThat(runs.get(r.id()).status()).isEqualTo(AiRun.Status.INTERRUPTED);
        assertThat(runs.events(r.id(), 0)).hasSize(1);
        assertThat(runs.create(s.id(), "abandoned", "hash", "text").fresh()).isFalse();
        assertThat(sessionService.history(s.id())).isEmpty();
    }

    @Test
    void urgentMessageBypassesModelAndSummaryEvenWithHistory() {
        var s = sessionService.create();
        for (int i = 0; i < 5; i++)
            chat.turn(s.id(), "考试压力", "全部", "v1", true, event -> {
            });
        var r = runtime.create(s.id(), command("我想伤害自己", "urgent"));
        completed(r.id());
        assertThat(usage.recent(r.id())).isEmpty();
        assertThat(sessionService.history(s.id()).getLast().content()).contains("急救");
        assertThat(sessionService.history(s.id()).getLast().citations()).isEmpty();
    }

    @Test
    void incompleteAuthoringDraftIsSavedButCannotBePublished() {
        var d = surveys.save(null, new SurveyDraftInput("", "", List.of(new Question("q", "", "SINGLE", true, List.of())), 0));
        assertThat(d.id()).isNotBlank();
        assertThatThrownBy(() -> surveys.publish(d.id(), d.revision(), false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(surveys.drafts()).anyMatch(saved -> saved.id().equals(d.id()));
    }

    @Test
    void answerDraftSurvivesReloadAndIsPrivateAndRemovedOnSubmission() {
        var s = surveys.published().getFirst();
        var a = new AnswerInput(s.questions().getFirst().id(), List.of("sometimes"), "");
        surveys.saveAnswers(s.surveyId(), new SurveySubmission(s.version(), List.of(a)));
        assertThat(surveys.answerDraft(s.surveyId(), s.version()).answers()).containsExactly(a);
        assertThatThrownBy(() -> surveys.saveAnswers(s.surveyId(), new SurveySubmission(s.version(), List.of(new AnswerInput(a.questionId(), List.of("fake"), ""))))).isInstanceOf(IllegalArgumentException.class);
        var who = TenantContext.require();
        try (var ignored = TenantContext.open(new LoginIdentity(who.tenantId(), who.tenantSlug(), who.tenantName(), "other", "other", "MEMBER"))) {
            assertThat(surveys.answerDraft(s.surveyId(), s.version()).answers()).isEmpty();
        }
        surveys.submit(s.surveyId(), new SurveySubmission(s.version(), s.questions().stream().map(q -> new AnswerInput(q.id(), List.of("sometimes"), "")).toList()));
        assertThat(surveys.answerDraft(s.surveyId(), s.version()).answers()).isEmpty();
    }

    @Test
    void aggregateBudgetIncludesEarlierStagesAndRestoresAfterScope() throws Exception {
        try (var context = RunContext.open("budget", () -> false, 500)) {
            RunContext.reserve(200);
            RunContext.reserve(300);
            assertThatThrownBy(() -> RunContext.reserve(1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(RunContext.id()).isNull();
    }
    @Test
    void commitCallbackFailureRollsBackMessagesMetricsAndRuntimeCompletionTogether() {
        var session = sessionService.create();
        var run = runs.create(session.id(), "rollback", "hash", "你好").run();
        assertThat(runs.start(run.id())).isTrue();
        int before = sessionService.metrics().size();
        assertThatThrownBy(() -> chat.turn(session.id(), "你好", "全部", "v1", true,
                event -> { }, result -> {
                    runs.finish(run.id(), AiRun.Status.COMPLETED, "done", result, null);
                    throw new IllegalStateException("rollback completed turn");
                })).isInstanceOf(IllegalStateException.class);
        assertThat(sessionService.history(session.id())).hasSize(1)
                .allMatch(message -> message.role().equals("user") && message.status().equals("failed"));
        assertThat(sessionService.metrics()).hasSize(before);
        assertThat(sessionService.session(session.id()).title()).isEqualTo("新的对话");
        assertThat(runs.get(run.id()).status()).isEqualTo(AiRun.Status.RUNNING);
        assertThat(runs.events(run.id(), 0)).noneMatch(event -> event.name().equals("done"));
        runs.finish(run.id(), AiRun.Status.FAILED, "error", Map.of("message", "test cleanup"), "test cleanup");
    }

}
