package com.mindhaven;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.application.auth.AuthService;
import com.mindhaven.application.chat.ChatService;
import com.mindhaven.application.chat.ContextPlanner;
import com.mindhaven.application.knowledge.KnowledgeService;
import com.mindhaven.application.questionnaire.QuestionnaireService;
import com.mindhaven.config.Settings;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.RecordStore;
import com.mindhaven.security.TenantContext;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:sqlite::memory:",
      "mindhaven.ai-mode=demo",
      "mindhaven.vector-mode=local"
    })
@AutoConfigureMockMvc
class ApplicationTest {
  @Autowired MockMvc mvc;
  @Autowired ChatService chat;
  @Autowired RecordStore store;
  @Autowired KnowledgeService knowledge;
  @Autowired ContextPlanner planner;
  @Autowired Settings settings;
  @Autowired ObjectMapper json;
  @Autowired AuthService auth;
  @Autowired QuestionnaireService questionnaires;
  Cookie cookie;
  TenantContext.Scope scope;

  @BeforeEach
  void tenant() {
    var login =
        auth.register("t-" + UUID.randomUUID(), "Test school", "admin", "password-test-123");
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
    return questionnaires.submit(
        s.surveyId(),
        new QuestionnaireService.Submission(
            s.version(),
            s.questions().stream()
                .map(q -> new AnswerInput(q.id(), List.of("sometimes"), ""))
                .toList()));
  }

  @Test
  void surveyValidatesAndPersistsReport() throws Exception {
    var s = questionnaires.published().getFirst();
    perform(
            post("/api/surveys/" + s.surveyId() + "/submit")
                .contentType("application/json")
                .content("{\"version\":1,\"answers\":[]}"))
        .andExpect(status().isBadRequest());
    var r = response();
    assertThat(r.score()).isEqualTo(5);
    assertThat(r.maxScore()).isEqualTo(15);
    perform(get("/api/reports"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].answers.length()").value(5));
  }

  @Test
  void postRoundTripAndHugIsIdempotent() throws Exception {
    String raw =
        perform(
                post("/api/posts")
                    .contentType("application/json")
                    .content("{\"content\":\"今天完成了一件小事\",\"mood\":\"小确幸\"}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(raw).get("id").asText();
    perform(post("/api/posts/" + id + "/hug")).andExpect(jsonPath("$.hugs").value(1));
    perform(post("/api/posts/" + id + "/hug")).andExpect(jsonPath("$.hugs").value(1));
    perform(delete("/api/posts/" + id)).andExpect(status().isOk());
    assertThat(store.get("posts", id, Post.class)).isEmpty();
  }

  @Test
  void retrievalFiltersVersionAndTopicAndHandlesMissingKnowledge() {
    assertThat(knowledge.search("考试作业压力", "学业压力", "v1", 3))
        .extracting(Citation::id)
        .contains("stress-v1");
    assertThat(knowledge.search("考试作业压力", "睡眠", "v1", 3)).allMatch(c -> c.topic().equals("睡眠"));
    assertThat(knowledge.search("考试压力", "全部", "missing-version", 3)).isEmpty();
    assertThat(knowledge.search("量子纠缠火星轨道", "全部", "v1", 3)).isEmpty();
  }

  @Test
  void chatPersistsCitationsAndSeparatesSessions() {
    var a = chat.create();
    var b = chat.create();
    chat.turn(a.id(), "考试压力很大", "全部", "v1", true, true, (n, d) -> {});
    assertThat(chat.history(a.id())).hasSize(2).allMatch(m -> m.status().equals("complete"));
    assertThat(chat.history(a.id()).getLast().citations())
        .extracting(Citation::id)
        .contains("stress-v1");
    assertThat(chat.history(b.id())).isEmpty();
  }

  @Test
  void incrementalSummaryAdvancesAndPlannerDoesNotInjectCoveredMessages() {
    var s = chat.create();
    for (int i = 0; i < 9; i++)
      chat.turn(s.id(), "第" + i + "次记录，考试压力让我紧张", "学业压力", "v1", true, true, (n, d) -> {});
    Summary summary = chat.summary(s.id()).orElseThrow();
    assertThat(summary.version()).isGreaterThanOrEqualTo(2);
    assertThat(summary.coveredThroughSeq()).isGreaterThan(2);
    var plan =
        planner.plan(
            chat.history(s.id()),
            summary,
            "我今天可以做什么",
            knowledge.search("压力", "学业压力", "v1", 3),
            settings);
    assertThat(plan.estimate())
        .isLessThanOrEqualTo(settings.contextBudget() - settings.outputBudget());
    assertThat(plan.messages().get(0).getText()).contains("历史摘要");
    int turnMessages = plan.messages().size() - 2;
    assertThat(turnMessages % 2).isZero();
    assertThat(
            plan.messages().subList(1, plan.messages().size() - 1).stream()
                .map(m -> m.getText())
                .toList())
        .doesNotContain(chat.history(s.id()).getFirst().content());
  }

  @Test
  void outputReserveRejectsOversizedInput() {
    assertThatThrownBy(() -> planner.plan(List.of(), null, "长".repeat(5000), List.of(), settings))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failedStreamIsNotIncludedInFutureContext() {
    var s = chat.create();
    assertThatThrownBy(
            () ->
                chat.turn(
                    s.id(),
                    "考试压力",
                    "全部",
                    "v1",
                    true,
                    true,
                    (n, d) -> {
                      if (n.equals("delta")) throw new IllegalStateException("disconnect");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(chat.history(s.id())).hasSize(1);
    assertThat(chat.history(s.id()).getFirst().status()).isEqualTo("failed");
    chat.turn(s.id(), "睡前担心", "全部", "v1", true, true, (n, d) -> {});
    assertThat(chat.history(s.id()).stream().filter(m -> m.status().equals("complete"))).hasSize(2);
  }

  @Test
  void safetyBranchDoesNotInventKnowledgeCitations() {
    var s = chat.create();
    chat.turn(s.id(), "我想伤害自己", "全部", "v1", true, true, (n, d) -> {});
    var reply = chat.history(s.id()).getLast();
    assertThat(reply.content()).contains("急救");
    assertThat(reply.citations()).isEmpty();
  }

  @Test
  void unknownSessionReturns404() throws Exception {
    perform(get("/api/sessions/missing/messages")).andExpect(status().isNotFound());
  }

  @Test
  void completedTurnSurvivesDoneEventDisconnect() {
    var session = chat.create();
    assertThatThrownBy(
            () ->
                chat.turn(
                    session.id(),
                    "考试压力",
                    "全部",
                    "v1",
                    true,
                    true,
                    (n, d) -> {
                      if (n.equals("done")) throw new IllegalStateException("disconnect");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(chat.history(session.id())).hasSize(2).allMatch(m -> m.status().equals("complete"));
  }

  @Test
  void rejectsCrossOriginBrowserWrites() throws Exception {
    perform(post("/api/sessions").header("Origin", "https://unrelated.example"))
        .andExpect(status().isForbidden());
  }

  @Test
  void reportAnalysisIsGeneratedAndSaved() throws Exception {
    String id = response().id();
    perform(post("/api/reports/" + id + "/analysis"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("demo"));
    perform(get("/api/reports/" + id + "/analysis"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isNotEmpty());
  }

  @Test
  void genericQuestionWordsDoNotCreateFalseKnowledgeMatches() {
    assertThat(knowledge.search("量子纠缠和火星轨道是什么", "全部", "v1", 4)).isEmpty();
  }
}
