package com.mindhaven.web.controller;

import com.mindhaven.application.knowledge.KnowledgeService;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class KnowledgeController {
  private final KnowledgeService knowledge;

  public KnowledgeController(KnowledgeService knowledge) {
    this.knowledge = knowledge;
  }

  @GetMapping("/knowledge")
  public Object knowledge() {
    return knowledge.all();
  }

  @GetMapping("/knowledge/{id}")
  public Object source(@PathVariable String id) {
    return knowledge.get(id);
  }

  public record ImportInput(
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 40) String topic,
      @NotBlank @Size(max = 40) String version,
      @Size(max = 500) String sourceUrl,
      @NotBlank @Size(max = 700) String text) {}

  @PostMapping("/knowledge")
  public Object add(@Valid @RequestBody ImportInput i) {
    TenantContext.requireAdmin();
    return knowledge.add(
        i.title(), i.topic(), i.version(), i.sourceUrl() == null ? "" : i.sourceUrl(), i.text());
  }

  @PostMapping("/knowledge/index")
  public Object index() {
    TenantContext.requireAdmin();
    return Map.of("indexed", knowledge.index());
  }
}
