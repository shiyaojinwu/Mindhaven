package com.mindhaven.web.controller;

import com.mindhaven.config.RetrievalSettings;
import com.mindhaven.config.Settings;
import com.mindhaven.domain.model.Models.*;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class HealthController {
  private final Settings settings;

  private final RetrievalSettings retrieval;

  public HealthController(Settings settings, RetrievalSettings retrieval) {
    this.settings = settings;
    this.retrieval = retrieval;
  }

  @GetMapping("/health")
  public Object health() {
    return Map.of(
        "status",
        "ok",
        "mode",
        settings.aiMode(),
        "retrieval",
        settings.vectorMode().equals("qdrant") ? "qdrant" : "local-keyword",
        "retrievalStrategy",
        settings.vectorMode().equals("qdrant")
            ? (retrieval.mode().equals("hybrid") ? "hybrid-rrf" : "dense")
            : "bm25",
        "retrievalConfig",
        retrieval,
        "multiTenant",
        true);
  }
}
