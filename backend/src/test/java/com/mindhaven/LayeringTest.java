package com.mindhaven;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Enforces Controller -> Service -> Manager -> Mapper and independent integration adapters.
 */
class LayeringTest {
    @Test
    void dependencyBoundaries() throws Exception {
        Path root = Path.of("src/main/java/com/mindhaven");
        assertThat(Files.exists(root.resolve("application"))).isFalse();
        assertThat(Files.exists(root.resolve("domain"))).isFalse();
        assertThat(Files.exists(root.resolve("infrastructure"))).isFalse();
        try (var files = Files.walk(root)) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String path = root.relativize(file).toString(), source = Files.readString(file);
                if (path.startsWith("controller/"))
                    assertThat(source).as(path).doesNotContain("com.mindhaven.manager.", "com.mindhaven.mapper.", "JdbcTemplate", "com.baomidou.", "org.apache.ibatis.");
                if (path.startsWith("service/"))
                    assertThat(source).as(path).doesNotContain("com.mindhaven.controller.", "com.mindhaven.mapper.", "com.mindhaven.model.entity.", "JdbcTemplate", "com.baomidou.", "org.apache.ibatis.");
                if (path.startsWith("service/knowledge/"))
                    assertThat(source).as(path).doesNotContain("org.springframework.ai.vectorstore", "org.springframework.ai.document");
                if (path.startsWith("manager/") || path.startsWith("integration/") || path.startsWith("mapper/"))
                    assertThat(source).as(path).doesNotContain("com.mindhaven.controller.", "com.mindhaven.service.");
            }
        }
    }
}
