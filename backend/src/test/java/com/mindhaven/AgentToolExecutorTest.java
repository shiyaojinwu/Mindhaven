package com.mindhaven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.config.AgentSettings;
import com.mindhaven.model.course.Course;
import com.mindhaven.security.LoginIdentity;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.agent.AgentToolExecutor;
import com.mindhaven.service.chat.ContextPlanner;
import com.mindhaven.service.course.LearningService;
import com.mindhaven.service.knowledge.KnowledgeService;
import com.mindhaven.service.questionnaire.QuestionnaireService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentToolExecutorTest {
    KnowledgeService knowledge = mock(KnowledgeService.class);
    LearningService learning = mock(LearningService.class);
    QuestionnaireService surveys = mock(QuestionnaireService.class);
    AgentToolExecutor executor = new AgentToolExecutor(knowledge, learning, surveys, new ObjectMapper(), new AgentSettings(true, 4, 3, 2, 1200));
    LoginIdentity identity = new LoginIdentity("tenant", "slug", "test", "user", "name", "MEMBER");

    @Test
    void forbidsUnknownToolsAndModelSuppliedTenantBeforeAccessingData() {
        try (var scope = TenantContext.open(identity)) {
            assertThatThrownBy(() -> executor.execute("deleteCourse", "{}", "全部", "v1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> executor.execute("findCourses", "{\"query\":\"\",\"tenantId\":\"other\"}", "全部", "v1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> executor.execute("findCourses", "{\"query\":1}", "全部", "v1")).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(knowledge, learning, surveys);
    }

    @Test
    void requiresAuthenticatedContext() {
        assertThatThrownBy(() -> executor.execute("findCourses", "{\"query\":\"\"}", "全部", "v1")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(learning);
    }

    @Test
    void boundedResultsContainOnlyPublishedServiceDataAndServerIds() {
        when(learning.courses()).thenReturn(List.of(new Course("real-id", "睡眠课程", "睡眠", 3, "介绍".repeat(3000), "正文", null)));
        try (var scope = TenantContext.open(identity)) {
            var result = executor.execute("findCourses", "{\"query\":\"睡眠\"}", "全部", "v1");
            assertThat(result.recommendations()).hasSize(1);
            assertThat(result.recommendations().getFirst().id()).isEqualTo("real-id");
            assertThat(ContextPlanner.estimate(result.payload())).isLessThanOrEqualTo(1200);
            assertThat(result.payload()).doesNotContain("正文");
        }
    }
    @Test
    void pagesExposeMatchingTotalAndAdvanceWithoutSkippingTrimmedItems() throws Exception {
        when(learning.courses()).thenReturn(java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> new Course(String.format("%02d", i), "课程" + i, "学习", 5, "介绍", "正文")).toList());
        var json = new ObjectMapper();
        try (var scope = TenantContext.open(identity)) {
            var first = json.readTree(executor.execute("findCourses", "{}", "全部", "v1").payload());
            assertThat(first.path("total").asInt()).isEqualTo(12);
            assertThat(first.path("hasMore").asBoolean()).isTrue();
            int next = first.path("nextOffset").asInt();
            assertThat(next).isEqualTo(first.path("items").size()).isPositive();
            var second = json.readTree(executor.execute("findCourses", "{\"offset\":" + next + ",\"limit\":2}", "全部", "v1").payload());
            assertThat(second.path("items").get(0).path("id").asText()).isEqualTo(String.format("%02d", next));
            var end = json.readTree(executor.execute("findCourses", "{\"offset\":12}", "全部", "v1").payload());
            assertThat(end.path("hasMore").asBoolean()).isFalse();
            assertThat(end.path("nextOffset").isNull()).isTrue();
            assertThatThrownBy(() -> executor.execute("findCourses", "{\"offset\":-1}", "全部", "v1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> executor.execute("findCourses", "{\"limit\":1.5}", "全部", "v1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> executor.execute("searchKnowledge", "{}", "全部", "v1")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void oneCallReturnsThirtyRealCardsWithCompactModelPayload() throws Exception {
        when(learning.courses()).thenReturn(java.util.stream.IntStream.range(0, 103)
                .mapToObj(i -> new Course(String.format("id-%03d", i), "课程" + i, "学习", 5, "介绍".repeat(200), "正文")).toList());
        try (var scope = TenantContext.open(identity)) {
            var result = executor.execute("findCourses", "{\"limit\":30}", "全部", "v1");
            var payload = new ObjectMapper().readTree(result.payload());
            assertThat(result.recommendations()).hasSize(30);
            assertThat(payload.path("total").asInt()).isEqualTo(103);
            assertThat(payload.path("nextOffset").asInt()).isEqualTo(30);
            assertThat(payload.path("items").size()).isEqualTo(30);
            assertThat(payload.path("items").get(0).has("description")).isFalse();
            assertThatThrownBy(() -> executor.execute("findCourses", "{\"limit\":31}", "全部", "v1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(learning, times(1)).courses();
    }

}
