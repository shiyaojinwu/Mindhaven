package com.mindhaven;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mindhaven.application.auth.AuthService;
import com.mindhaven.application.chat.ChatService;
import com.mindhaven.application.knowledge.KnowledgeService;
import com.mindhaven.application.questionnaire.QuestionnaireService;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.RecordStore;
import com.mindhaven.security.TenantContext;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:sqlite::memory:",
      "mindhaven.ai-mode=demo",
      "mindhaven.vector-mode=local"
    })
@AutoConfigureMockMvc
class TenantTest {
  @Autowired MockMvc mvc;
  @Autowired AuthService auth;
  @Autowired QuestionnaireService surveys;
  @Autowired RecordStore store;
  @Autowired ChatService chat;
  @Autowired KnowledgeService knowledge;
  @Autowired JdbcTemplate jdbc;
  AuthService.Login a, b, member;

  Cookie cookie(AuthService.Login login) {
    return new Cookie("mindhaven_session", login.token());
  }

  @BeforeEach
  void setup() {
    a = auth.register("a-" + UUID.randomUUID(), "School A", "admin", "password-test-123");
    b = auth.register("b-" + UUID.randomUUID(), "School B", "admin", "password-test-123");
    try (var scope = TenantContext.open(a.identity())) {
      auth.addMember("student", "password-test-123");
    }
    member = auth.login(a.identity().tenantSlug(), "student", "password-test-123");
  }

  @Test
  void loginUsesServerSessionAndLogoutRevokesIt() throws Exception {
    mvc.perform(get("/api/sessions").header("X-Tenant-Id", a.identity().tenantId()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/auth/me").cookie(cookie(a)).header("X-Tenant-Id", b.identity().tenantId()))
        .andExpect(jsonPath("$.tenantId").value(a.identity().tenantId()));
    mvc.perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content(
                    "{\"tenantSlug\":\""
                        + a.identity().tenantSlug()
                        + "\",\"username\":\"admin\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE token_hash=?", Integer.class, a.token()))
        .isZero();
    mvc.perform(post("/api/auth/logout").cookie(cookie(a))).andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").cookie(cookie(a))).andExpect(status().isUnauthorized());
    assertThatThrownBy(TenantContext::require).isInstanceOf(HttpProblem.class);
  }

  @Test
  void memberCannotManageQuestionnairesMembersOrKnowledge() throws Exception {
    for (String path : List.of("/api/admin/surveys", "/api/admin/members"))
      mvc.perform(get(path).cookie(cookie(member))).andExpect(status().isForbidden());
    mvc.perform(post("/api/knowledge/index").cookie(cookie(member)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/surveys")
                .cookie(cookie(member))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void sharedResourcesAreTenantScopedAndPrivateResourcesAreUserScoped() throws Exception {
    String session, id;
    try (var scope = TenantContext.open(a.identity())) {
      session = chat.create().id();
      id = knowledge.add("A私有材料", "自定义", "v9", "", "只有A机构可以使用的知识").id();
      store.put("posts", "same-id", new Post("same-id", "A私密", "记录", 0, ChatService.now()));
    }
    mvc.perform(get("/api/sessions/" + session + "/messages").cookie(cookie(b)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/sessions/" + session + "/messages").cookie(cookie(member)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/knowledge/" + id).cookie(cookie(b))).andExpect(status().isNotFound());
    mvc.perform(get("/api/knowledge/" + id).cookie(cookie(member))).andExpect(status().isOk());
    try (var scope = TenantContext.open(member.identity())) {
      assertThat(store.list("posts", Post.class)).isEmpty();
      store.delete("posts", "same-id");
    }
    try (var scope = TenantContext.open(a.identity())) {
      assertThat(store.get("posts", "same-id", Post.class)).isPresent();
    }
  }

  List<Question> questions() {
    return List.of(
        new Question(
            "one",
            "单选",
            "SINGLE",
            true,
            List.of(new Option("x", "选项X", 2), new Option("y", "选项Y", 5))),
        new Question(
            "multi",
            "多选",
            "MULTIPLE",
            true,
            List.of(new Option("m", "选项M", 3), new Option("n", "选项N", 4))),
        new Question("text", "想说的话", "TEXT", true, List.of()));
  }

  QuestionnaireService.Submission submission(int version) {
    return new QuestionnaireService.Submission(
        version,
        List.of(
            new AnswerInput("one", List.of("x"), ""),
            new AnswerInput("multi", List.of("m", "n"), ""),
            new AnswerInput("text", List.of(), "希望多一点倾听")));
  }

  @Test
  void publishedVersionsAndAnswersAreImmutableAndAdminResponsesStayWithinTenant() throws Exception {
    SurveyDraft d;
    Assessment r;
    try (var scope = TenantContext.open(a.identity())) {
      d = surveys.save(null, new QuestionnaireService.DraftInput("自定义问卷", "说明", questions(), 0));
      assertThat(surveys.published()).noneMatch(x -> x.title().equals("自定义问卷"));
      d = surveys.publish(d.id(), d.revision(), false);
    }
    String id = d.id();
    try (var scope = TenantContext.open(member.identity())) {
      r = surveys.submit(id, submission(1));
      assertThat(r.score()).isEqualTo(9);
      assertThat(r.maxScore()).isEqualTo(12);
    }
    try (var scope = TenantContext.open(a.identity())) {
      var revisedQuestions = new ArrayList<>(questions());
      revisedQuestions.set(
          0,
          new Question(
              "one",
              "修改后的题目",
              "SINGLE",
              true,
              List.of(new Option("x", "修改后的选项", 20), new Option("y", "另一个选项", 30))));
      var edited =
          surveys.save(
              id,
              new QuestionnaireService.DraftInput("新标题", "新说明", revisedQuestions, d.revision()));
      assertThat(
              surveys.published().stream()
                  .filter(x -> x.surveyId().equals(id))
                  .findFirst()
                  .orElseThrow()
                  .title())
          .isEqualTo("自定义问卷");
      var next = surveys.publish(id, edited.revision(), false);
      assertThat(next.publishedVersion()).isEqualTo(2);
      assertThat(surveys.responses(id).getFirst().assessment().surveyTitle()).isEqualTo("自定义问卷");
      assertThat(
              surveys.responses(id).getFirst().assessment().answers().getFirst().selectedLabels())
          .containsExactly("选项X");
      assertThat(store.get("assessments", r.id(), Assessment.class)).isEmpty();
    }
    try (var scope = TenantContext.open(member.identity())) {
      var oldResponse = surveys.submit(id, submission(1));
      assertThat(oldResponse.surveyVersion()).isEqualTo(1);
      assertThat(oldResponse.score()).isEqualTo(9);
      assertThat(oldResponse.answers().getFirst().title()).isEqualTo("单选");
      var newResponse = surveys.submit(id, submission(2));
      assertThat(newResponse.score()).isEqualTo(27);
      assertThat(newResponse.answers().getFirst().selectedLabels()).containsExactly("修改后的选项");
    }
    mvc.perform(get("/api/admin/surveys/" + id + "/responses").cookie(cookie(b)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/admin/surveys/" + id + "/responses").cookie(cookie(member)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/reports/" + r.id() + "/analysis").cookie(cookie(b)))
        .andExpect(status().isNotFound());
  }

  @Test
  void conflictingEditsArchiveAndMalformedAnswersAreRejected() {
    try (var scope = TenantContext.open(a.identity())) {
      var d = surveys.save(null, new QuestionnaireService.DraftInput("问卷", "说明", questions(), 0));
      var pub = surveys.publish(d.id(), d.revision(), false);
      assertThatThrownBy(
              () ->
                  surveys.save(
                      d.id(),
                      new QuestionnaireService.DraftInput("覆盖", "", questions(), d.revision())))
          .isInstanceOf(HttpProblem.class)
          .hasMessageContaining("修改");
      assertThatThrownBy(
              () -> surveys.submit(d.id(), new QuestionnaireService.Submission(1, List.of())))
          .isInstanceOf(IllegalArgumentException.class);
      for (var answer :
          List.of(
              new AnswerInput("unknown", List.of(), ""),
              new AnswerInput("one", List.of("bad"), ""),
              new AnswerInput("one", List.of("x", "y"), ""),
              new AnswerInput("one", List.of("x", "x"), ""))) {
        assertThatThrownBy(
                () ->
                    surveys.submit(d.id(), new QuestionnaireService.Submission(1, List.of(answer))))
            .isInstanceOf(IllegalArgumentException.class);
      }
      assertThat(store.list("assessments", Assessment.class)).isEmpty();
      surveys.publish(d.id(), pub.revision(), true);
      assertThatThrownBy(() -> surveys.submit(d.id(), submission(1)))
          .isInstanceOf(HttpProblem.class);
    }
  }

  @Test
  void asyncChatCarriesTenantAndDoesNotLeakOnWorkerReuse() throws Exception {
    for (var login : List.of(a, b, member, a)) {
      String id;
      try (var scope = TenantContext.open(login.identity())) {
        id = chat.create().id();
      }
      var result =
          mvc.perform(
                  post("/api/sessions/" + id + "/chat")
                      .cookie(cookie(login))
                      .contentType("application/json")
                      .content(
                          "{\"message\":\"考试压力\",\"topic\":\"全部\",\"version\":\"v1\",\"rewrite\":true,\"compression\":true}"))
              .andExpect(request().asyncStarted())
              .andReturn();
      result.getAsyncResult(10000);
      mvc.perform(asyncDispatch(result))
          .andExpect(status().isOk())
          .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
      try (var scope = TenantContext.open(login.identity())) {
        assertThat(chat.history(id)).hasSize(2);
      }
      assertThatThrownBy(TenantContext::require).isInstanceOf(HttpProblem.class);
    }
  }

  @Test
  void duplicateTenantRegistrationIsConflictAndDoesNotCreateAnotherUser() throws Exception {
    mvc.perform(
            post("/api/auth/register")
                .contentType("application/json")
                .content(
                    "{\"tenantSlug\":\""
                        + a.identity().tenantSlug()
                        + "\",\"tenantName\":\"Collision\",\"username\":\"newadmin\",\"password\":\"password-test-123\"}"))
        .andExpect(status().isConflict());
  }
}
