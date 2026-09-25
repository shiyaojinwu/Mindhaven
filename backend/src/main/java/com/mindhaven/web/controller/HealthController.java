package com.mindhaven.web.controller;

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

  public HealthController(Settings settings) {
    this.settings = settings;
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
        "multiTenant",
        true);
  }
}
