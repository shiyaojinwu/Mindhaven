package com.mindhaven;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mindhaven.application.auth.AuthService;
import com.mindhaven.application.dto.CourseDtos;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.RecordStore;
import com.mindhaven.security.TenantContext;
import com.mindhaven.web.controller.CourseController;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:sqlite::memory:",
      "mindhaven.ai-mode=demo",
      "mindhaven.vector-mode=local"
    })
@AutoConfigureMockMvc
class CourseTest {
  @Autowired AuthService auth;
  @Autowired CourseController courses;
  @Autowired RecordStore store;
  @Autowired MockMvc mvc;
  AuthService.Login admin, member, other;

  @BeforeEach
  void setup() {
    admin = auth.register("c-" + UUID.randomUUID(), "课程测试", "admin", "password-test-123");
    other = auth.register("d-" + UUID.randomUUID(), "其他机构", "admin", "password-test-123");
    try (var scope = TenantContext.open(admin.identity())) {
      auth.addMember("student", "password-test-123");
    }
    member = auth.login(admin.identity().tenantSlug(), "student", "password-test-123");
  }

  CourseDtos.Input input(String title, int rev) {
    return new CourseDtos.Input(title, "压力", 5, "简介", "第一段\n\n第二段", rev);
  }

  @Test
  void publicationDraftAndArchivePreserveProgress() throws Exception {
    CourseDtos.Draft d;
    try (var scope = TenantContext.open(admin.identity())) {
      d = courses.create(input("测试课程", 0));
      assertThat(store.get("courses", d.id(), Course.class)).isEmpty();
      d = courses.publish(d.id(), new CourseDtos.Revision(d.revision()));
      var changed = courses.save(d.id(), input("新标题", d.revision()));
      assertThat(store.get("courses", d.id(), Course.class).orElseThrow().title())
          .isEqualTo("测试课程");
      d = courses.publish(d.id(), new CourseDtos.Revision(changed.revision()));
      assertThat(store.get("courses", d.id(), Course.class).orElseThrow().title()).isEqualTo("新标题");
    }
    mvc.perform(
            post("/api/courses/" + d.id() + "/complete")
                .cookie(new Cookie("mindhaven_session", member.token())))
        .andExpect(status().isOk());
    try (var scope = TenantContext.open(admin.identity())) {
      courses.archive(d.id(), new CourseDtos.Revision(d.revision()));
      assertThat(store.get("courses", d.id(), Course.class)).isEmpty();
    }
    mvc.perform(
            post("/api/courses/" + d.id() + "/complete")
                .cookie(new Cookie("mindhaven_session", member.token())))
        .andExpect(status().isNotFound());
    try (var scope = TenantContext.open(member.identity())) {
      assertThat(store.get("progress", d.id(), Progress.class)).isPresent();
    }
  }

  @Test
  void managementRequiresRoleAndTenantAndRejectsStaleEdits() throws Exception {
    String id;
    try (var scope = TenantContext.open(admin.identity())) {
      var d = courses.create(input("自定义课", 0));
      id = d.id();
      courses.save(id, input("修改", d.revision()));
      assertThatThrownBy(() -> courses.save(id, input("过期编辑", d.revision())))
          .isInstanceOf(HttpProblem.class);
    }
    mvc.perform(get("/api/admin/courses").cookie(new Cookie("mindhaven_session", member.token())))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/courses/" + id + "/publish")
                .cookie(new Cookie("mindhaven_session", other.token()))
                .contentType("application/json")
                .content("{\"expectedRevision\":1}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/admin/courses")
                .cookie(new Cookie("mindhaven_session", admin.token()))
                .contentType("application/json")
                .content(
                    "{\"title\":\"test\",\"category\":\"c\",\"minutes\":0,\"intro\":\"i\",\"content\":\"body\"}"))
        .andExpect(status().isBadRequest());
    try (var scope = TenantContext.open(other.identity())) {
      assertThat(courses.list()).noneMatch(d -> d.id().equals(id));
    }
  }

  @Test
  void existingSeedsCanBeEditedWithoutChangingPublishedContent() {
    try (var scope = TenantContext.open(admin.identity())) {
      var d = courses.list().getFirst();
      var original = d.course();
      var draft = courses.save(d.id(), input("修改内置课程", d.revision()));
      assertThat(store.get("courses", d.id(), Course.class).orElseThrow()).isEqualTo(original);
      assertThat(draft.revision()).isEqualTo(1);
    }
  }
}
