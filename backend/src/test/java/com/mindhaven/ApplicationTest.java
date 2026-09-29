package com.mindhaven;

import com.mindhaven.service.chat.SessionService;
import com.mindhaven.model.chat.ChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.config.Settings;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.model.chat.CitationCheck;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.Summary;
import com.mindhaven.model.community.Post;
import com.mindhaven.model.dto.AnswerInput;
import com.mindhaven.model.dto.ChatCommand;
import com.mindhaven.model.dto.SurveySubmission;
import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.model.questionnaire.Assessment;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.auth.AuthService;
import com.mindhaven.service.chat.ChatService;
import com.mindhaven.service.chat.ContextPlanner;
import com.mindhaven.service.knowledge.KnowledgeService;
import com.mindhaven.service.questionnaire.QuestionnaireService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "mindhaven.ai-mode=demo", "mindhaven.vector-mode=local", "mindhaven.context-budget=12000"})
@AutoConfigureMockMvc
class ApplicationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ChatService chat;
    @Autowired
    SessionService sessionService;
    @Autowired
    RecordManager store;
    @Autowired
    KnowledgeService knowledge;
    @Autowired
    ContextPlanner planner;
    @Autowired
    Settings settings;
    @Autowired
    ObjectMapper json;
    @Autowired
    AuthService auth;
    @Autowired
    QuestionnaireService questionnaires;
    Cookie cookie;
    TenantContext.Scope scope;

    @BeforeEach
    void tenant() {
        var login = auth.register("t-" + UUID.randomUUID(), "Test school", "admin", "password-test-123");
        cookie = new Cookie("mindhaven_session", login.token());
        scope = TenantContext.open(login.identity());
    }

    @AfterEach
    void clear() {
        scope.close();
    }

    ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.cookie(cookie));
    }

    Assessment response() {
        var s = questionnaires.published().getFirst();
        return questionnaires.submit(s.surveyId(), new SurveySubmission(s.version(), s.questions().stream().map(q -> new AnswerInput(q.id(), List.of("sometimes"), "")).toList()));
    }

    @Test
    void clientCompressionFlagCannotControlServerPolicy() throws Exception {
        var command = json.readValue("{\"message\":\"hello\",\"topic\":\"全部\",\"version\":\"v1\",\"rewrite\":true,\"compression\":false,\"requestId\":\"r\"}", ChatCommand.class);
        assertThat(json.valueToTree(command).has("compression")).isFalse();
        perform(get("/api/health")).andExpect(jsonPath("$.contextConfig.compressionEnabled").value(true));
    }

    @Test
    void surveyValidatesAndPersistsReport() throws Exception {
        var s = questionnaires.published().getFirst();
        perform(post("/api/surveys/" + s.surveyId() + "/submit").contentType("application/json").content("{\"version\":1,\"answers\":[]}")).andExpect(status().isBadRequest());
        var r = response();
        assertThat(r.score()).isEqualTo(5);
        assertThat(r.maxScore()).isEqualTo(15);
        perform(get("/api/reports")).andExpect(status().isOk()).andExpect(jsonPath("$[0].answers.length()").value(5));
    }

    @Test
    void postRoundTripAndHugIsIdempotent() throws Exception {
        String raw = perform(post("/api/posts").contentType("application/json").content("{\"content\":\"今天完成了一件小事\",\"mood\":\"小确幸\"}")).andReturn().getResponse().getContentAsString();
        String id = json.readTree(raw).get("id").asText();
        perform(post("/api/posts/" + id + "/hug")).andExpect(jsonPath("$.hugs").value(1));
        perform(post("/api/posts/" + id + "/hug")).andExpect(jsonPath("$.hugs").value(1));
        perform(delete("/api/posts/" + id)).andExpect(status().isOk());
        assertThat(store.get("posts", id, Post.class)).isEmpty();
    }

    @Test
    void retrievalFiltersVersionAndTopicAndHandlesMissingKnowledge() {
        assertThat(knowledge.search("考试作业压力", "学业压力", "v1", 3)).extracting(Citation::id).contains("stress-v1");
        assertThat(knowledge.search("考试作业压力", "睡眠", "v1", 3)).allMatch(c -> c.topic().equals("睡眠"));
        assertThat(knowledge.search("考试压力", "全部", "missing-version", 3)).isEmpty();
        assertThat(knowledge.search("量子纠缠火星轨道", "全部", "v1", 3)).isEmpty();
    }

    @Test
    void chatPersistsCitationsAndSeparatesSessions() {
        var a = sessionService.create();
        var b = sessionService.create();
        chat.turn(a.id(), "考试压力很大", "全部", "v1", true, event -> {
        });
        assertThat(sessionService.history(a.id())).hasSize(2).allMatch(m -> m.status().equals("complete"));
        assertThat(sessionService.history(a.id()).getLast().citations()).extracting(Citation::id).contains("stress-v1");
        assertThat(sessionService.history(b.id())).isEmpty();
        assertThat(sessionService.history(a.id()).getLast().citationCheck().status()).isEqualTo(CitationCheck.Status.VALID);
        assertThat(sessionService.metrics().getFirst().retrievalMode()).isEqualTo("bm25");
        assertThat(sessionService.metrics().getFirst().contextIds()).contains("stress-v1");
    }

    @Test
    void incrementalSummaryAdvancesAndPlannerDoesNotInjectCoveredMessages() {
        var s = sessionService.create();
        for (int i = 0; i < 60 && sessionService.summary(s.id()).map(Summary::version).orElse(0) < 2; i++)
            chat.turn(s.id(), "第" + i + "次记录，考试压力让我紧张", "学业压力", "v1", true, event -> {
            });
        Summary summary = sessionService.summary(s.id()).orElseThrow();
        assertThat(summary.version()).isGreaterThanOrEqualTo(2);
        assertThat(summary.coveredThroughSeq()).isGreaterThan(2);
        var plan = planner.plan(sessionService.history(s.id()), summary, "我今天可以做什么", knowledge.search("压力", "学业压力", "v1", 3), settings);
        assertThat(plan.estimate()).isLessThanOrEqualTo(settings.contextBudget() - settings.outputBudget());
        assertThat(plan.messages().get(0).getText()).doesNotContain(summary.content());
        assertThat(plan.messages().get(1).getText()).contains("<conversation_summary>", summary.content());
        int turnMessages = plan.messages().size() - 3;
        assertThat(turnMessages % 2).isZero();
        assertThat(plan.messages().subList(2, plan.messages().size() - 1).stream().map(m -> m.getText()).toList()).doesNotContain(sessionService.history(s.id()).getFirst().content());
    }

    @Test
    void structuredContextKeepsRolesAndBudgetsEscapedDocuments() {
        var large = new Citation("large", "large", "topic", "v1", "", "&".repeat(3000), 1);
        var small = new Citation("small", "title", "topic", "v1", "", "相关原文", .8);
        var history = List.of(new ChatMessage("u", "s", 1, "user", "此前的问题", "now", List.of(), "complete"), new ChatMessage("a", "s", 2, "assistant", "此前的回答", "now", List.of(), "complete"));
        var plan = planner.plan(history, null, "当前问题<&", List.of(large, small), settings);
        assertThat(plan.citations()).extracting(Citation::id).containsExactly("small");
        assertThat(plan.messages()).hasSize(4);
        assertThat(plan.messages().get(1).getText()).isEqualTo("此前的问题");
        assertThat(plan.messages().get(2).getMessageType().getValue()).isEqualTo("assistant");
        assertThat(plan.messages().getLast().getText()).contains("<reference_documents>", "id=\"small\"", "<current_question>", "当前问题&lt;&amp;");
        assertThat(plan.estimate()).isEqualTo(plan.messages().stream().mapToInt(m -> ContextPlanner.estimate(m.getText())).sum()).isLessThanOrEqualTo(settings.contextBudget() - settings.outputBudget());
        var plain = planner.plan(List.of(), null, "原话<&", List.of(), settings);
        assertThat(plain.messages()).hasSize(2);
        assertThat(plain.messages().getLast().getText()).isEqualTo("原话<&");
    }

    @Test
    void outputReserveRejectsOversizedInput() {
        assertThatThrownBy(() -> planner.plan(List.of(), null, "长".repeat(5000), List.of(), settings)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedStreamIsNotIncludedInFutureContext() {
        var s = sessionService.create();
        assertThatThrownBy(() -> chat.turn(s.id(), "考试压力", "全部", "v1", true, event -> {
            if (event instanceof ChatEvent.Delta) throw new IllegalStateException("disconnect");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(sessionService.history(s.id())).hasSize(1);
        assertThat(sessionService.history(s.id()).getFirst().status()).isEqualTo("failed");
        chat.turn(s.id(), "睡前担心", "全部", "v1", true, event -> {
        });
        assertThat(sessionService.history(s.id()).stream().filter(m -> m.status().equals("complete"))).hasSize(2);
    }

    @Test
    void safetyBranchDoesNotInventKnowledgeCitations() {
        var s = sessionService.create();
        chat.turn(s.id(), "我想伤害自己", "全部", "v1", true, event -> {
        });
        var reply = sessionService.history(s.id()).getLast();
        assertThat(reply.content()).contains("急救");
        assertThat(reply.citations()).isEmpty();
    }

    @Test
    void unknownSessionReturns404() throws Exception {
        perform(get("/api/sessions/missing/messages")).andExpect(status().isNotFound());
    }

    @Test
    void completedTurnSurvivesDoneEventDisconnect() {
        var session = sessionService.create();
        assertThatThrownBy(() -> chat.turn(session.id(), "考试压力", "全部", "v1", true, event -> {
            if (event instanceof ChatEvent.Done) throw new IllegalStateException("disconnect");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(sessionService.history(session.id())).hasSize(2).allMatch(m -> m.status().equals("complete"));
    }

    @Test
    void rejectsCrossOriginBrowserWrites() throws Exception {
        perform(post("/api/sessions").header("Origin", "https://unrelated.example")).andExpect(status().isForbidden());
    }

    @Test
    void reportAnalysisIsGeneratedAndSaved() throws Exception {
        String id = response().id();
        perform(post("/api/reports/" + id + "/analysis")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("demo"));
        perform(get("/api/reports/" + id + "/analysis")).andExpect(status().isOk()).andExpect(jsonPath("$.content").isNotEmpty());
    }

    @Test
    void bareTopicCannotInheritFactsFromMatchingKnowledge() {
        knowledge.add("吃饭", "生活", "v1", "", "慢慢吃，演示资料");
        var session = sessionService.create();
        chat.turn(session.id(), "吃饭", "全部", "v1", true, event -> {
        });
        var answer = sessionService.history(session.id()).getLast();
        assertThat(answer.content()).isNotBlank().doesNotContain("慢慢吃");
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.citationCheck().status()).isEqualTo(CitationCheck.Status.NOT_REQUIRED);
        assertThat(sessionService.metrics().getFirst().retrievalMode()).isEqualTo("clarification");
        assertThat(sessionService.metrics().getFirst().model()).isEqualTo("deterministic-demo");
    }

    @Test
    void contextualPronounStillUsesHistoryAndRetrieval() {
        var session = sessionService.create();
        chat.turn(session.id(), "最近考试压力很大", "全部", "v1", true, event -> {
        });
        chat.turn(session.id(), "那怎么办？", "全部", "v1", true, event -> {
        });
        assertThat(sessionService.metrics().getFirst().retrievalMode()).isEqualTo("bm25");
        assertThat(sessionService.metrics().getFirst().rewrittenQuery()).contains("考试压力");
        assertThat(sessionService.history(session.id()).getLast().citations()).extracting(Citation::id).contains("stress-v1");
    }

    @Test
    void genericQuestionWordsDoNotCreateFalseKnowledgeMatches() {
        assertThat(knowledge.search("量子纠缠和火星轨道是什么", "全部", "v1", 4)).isEmpty();
    }
}
