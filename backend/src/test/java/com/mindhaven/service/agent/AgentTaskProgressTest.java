package com.mindhaven.service.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AgentTaskProgressTest {
    @Test
    void canonicalArgumentsAndTaskIsolation() {
        var a = new AgentTaskProgress();
        var b = new AgentTaskProgress();
        String key = a.key("findCourses", "{\"query\":\"sleep\",\"offset\":0}");
        assertThat(key).isEqualTo(a.key("findCourses", "{ \"offset\": 0, \"query\": \"sleep\" }"));
        var result = new AgentToolExecutor.Result("{\"items\":[]}", List.of(), List.of(), null);
        a.confirmed(key, result);
        assertThat(a.cached(key)).isSameAs(result);
        assertThat(b.cached(key)).isNull();
        assertThat(a.stalled()).isFalse();
        a.repeated(); a.repeated(); a.repeated();
        assertThat(a.stalled()).isTrue();
        a.confirmed("page2", new AgentToolExecutor.Result("{\"items\":[{\"id\":\"new\"}]}", List.of(), List.of(), null));
        assertThat(a.stalled()).isFalse();
    }
}
