package com.mindhaven;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;

/** Keeps HTTP endpoints free of persistence and prevents services coupling back to controllers. */
class LayeringTest {
  @Test
  void dependencyBoundaries() throws Exception {
    Path root = Path.of("src/main/java/com/mindhaven");
    try (var files = Files.walk(root)) {
      for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String path = root.relativize(file).toString(), source = Files.readString(file);
        if (path.startsWith("web/"))
          assertThat(source)
              .as(path)
              .doesNotContain(
                  "JdbcTemplate",
                  "domain.port.RecordStore",
                  "infrastructure.persistence",
                  "com.baomidou.",
                  "org.apache.ibatis.");
        if (path.startsWith("application/"))
          assertThat(source)
              .as(path)
              .doesNotContain(
                  "com.mindhaven.web.",
                  "com.mindhaven.infrastructure.",
                  "JdbcTemplate",
                  "com.baomidou.",
                  "org.apache.ibatis.");
        if (path.startsWith("application/knowledge/"))
          assertThat(source)
              .as(path)
              .doesNotContain(
                  "org.springframework.ai.vectorstore", "org.springframework.ai.document");
        if (path.startsWith("infrastructure/"))
          assertThat(source)
              .as(path)
              .doesNotContain("com.mindhaven.web.", "com.mindhaven.application.");
      }
    }
  }
}
