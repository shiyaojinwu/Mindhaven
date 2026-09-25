package com.mindhaven;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.application.auth.AuthService;
import com.mindhaven.application.dto.CourseDtos;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.security.TenantContext;
import com.mindhaven.web.controller.CourseController;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:sqlite::memory:",
      "mindhaven.ai-mode=demo",
      "mindhaven.vector-mode=local",
      "mindhaven.video-max-size=1KB"
    })
@AutoConfigureMockMvc
class VideoTest {
  static Path dir;

  static {
    try {
      dir = Files.createTempDirectory("mindhaven-video-test-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add("mindhaven.media-dir", () -> dir.toString());
  }

  @AfterAll
  static void cleanup() throws Exception {
    try (var paths = Files.walk(dir)) {
      for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }

  @Autowired AuthService auth;
  @Autowired CourseController courses;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  AuthService.Login admin, member, other;

  Cookie cookie(AuthService.Login l) {
    return new Cookie("mindhaven_session", l.token());
  }

  @BeforeEach
  void setup() {
    admin = auth.register("v-" + UUID.randomUUID(), "视频测试", "admin", "password-test-123");
    other = auth.register("w-" + UUID.randomUUID(), "其他机构", "admin", "password-test-123");
    try (var scope = TenantContext.open(admin.identity())) {
      auth.addMember("student", "password-test-123");
    }
    member = auth.login(admin.identity().tenantSlug(), "student", "password-test-123");
  }

  byte[] bytes() {
    byte[] b = new byte[128];
    b[3] = 24;
    b[4] = 'f';
    b[5] = 't';
    b[6] = 'y';
    b[7] = 'p';
    b[8] = 'i';
    b[9] = 's';
    b[10] = 'o';
    b[11] = 'm';
    return b;
  }

  String upload() throws Exception {
    var response =
        mvc.perform(
                multipart("/api/admin/videos")
                    .file(new MockMultipartFile("file", "../../sample.mp4", "video/mp4", bytes()))
                    .cookie(cookie(admin)))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(response.getResponse().getContentAsString()).get("id").asText();
  }

  @Test
  void publicationControlsReadAndRangeAndArchive() throws Exception {
    String id = upload();
    assertThat(Files.exists(dir.resolve(admin.identity().tenantId()).resolve(id))).isTrue();
    mvc.perform(get("/api/videos/" + id)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/videos/" + id).cookie(cookie(other))).andExpect(status().isNotFound());
    mvc.perform(get("/api/videos/" + id).cookie(cookie(member))).andExpect(status().isNotFound());
    mvc.perform(get("/api/videos/" + id).cookie(cookie(admin)).header("Range", "bytes=0-15"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "bytes 0-15/128"))
        .andExpect(content().bytes(Arrays.copyOf(bytes(), 16)));
    mvc.perform(get("/api/videos/" + id).cookie(cookie(admin)).header("Range", "bytes=-8"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "bytes 120-127/128"))
        .andExpect(content().bytes(Arrays.copyOfRange(bytes(), 120, 128)));
    mvc.perform(get("/api/videos/" + id).cookie(cookie(admin)).header("Range", "bytes=0-1,4-5"))
        .andExpect(status().isRequestedRangeNotSatisfiable());
    CourseDtos.Draft d;
    try (var scope = TenantContext.open(admin.identity())) {
      d = courses.create(new CourseDtos.Input("视频课程", "测试", 2, "简介", "", 0, id));
      d = courses.publish(d.id(), new CourseDtos.Revision(d.revision()));
    }
    mvc.perform(get("/api/videos/" + id).cookie(cookie(member)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"));
    mvc.perform(get("/api/videos/" + id).cookie(cookie(member)).header("Range", "bytes=999-1000"))
        .andExpect(status().isRequestedRangeNotSatisfiable());
    try (var scope = TenantContext.open(admin.identity())) {
      courses.archive(d.id(), new CourseDtos.Revision(d.revision()));
    }
    mvc.perform(get("/api/videos/" + id).cookie(cookie(member))).andExpect(status().isNotFound());
  }

  @Test
  void rejectsMemberUploadBadFormatOversizeAndCrossTenantBinding() throws Exception {
    mvc.perform(
            multipart("/api/admin/videos")
                .file(new MockMultipartFile("file", "sample.mp4", "video/mp4", bytes()))
                .cookie(cookie(member)))
        .andExpect(status().isForbidden());
    mvc.perform(
            multipart("/api/admin/videos")
                .file(
                    new MockMultipartFile(
                        "file", "fake.mp4", "video/mp4", "<html>not video</html>".getBytes()))
                .cookie(cookie(admin)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            multipart("/api/admin/videos")
                .file(new MockMultipartFile("file", "big.mp4", "video/mp4", new byte[2048]))
                .cookie(cookie(admin)))
        .andExpect(status().isPayloadTooLarge());
    String id = upload();
    try (var scope = TenantContext.open(other.identity())) {
      assertThatThrownBy(
              () -> courses.create(new CourseDtos.Input("foreign", "test", 2, "intro", "", 0, id)))
          .isInstanceOf(NoSuchElementException.class);
    }
  }

  @Test
  void replacementDoesNotExposeDraftVideoUntilPublished() throws Exception {
    String first = upload(), second = upload();
    try (var scope = TenantContext.open(admin.identity())) {
      var d = courses.create(new CourseDtos.Input("视频", "测试", 2, "简介", "", 0, first));
      d = courses.publish(d.id(), new CourseDtos.Revision(d.revision()));
      d = courses.save(d.id(), new CourseDtos.Input("视频", "测试", 2, "简介", "", d.revision(), second));
      mvc.perform(get("/api/videos/" + first).cookie(cookie(member))).andExpect(status().isOk());
      mvc.perform(get("/api/videos/" + second).cookie(cookie(member)))
          .andExpect(status().isNotFound());
      courses.publish(d.id(), new CourseDtos.Revision(d.revision()));
    }
    mvc.perform(get("/api/videos/" + first).cookie(cookie(member)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/videos/" + second).cookie(cookie(member))).andExpect(status().isOk());
  }
}
