package com.mindhaven.controller;

import com.mindhaven.config.RetrievalSettings;
import com.mindhaven.config.Settings;
import com.mindhaven.config.ContextSettings;
import com.mindhaven.model.vo.HealthResponse;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final Settings settings;
    private final ContextSettings context;

    private final RetrievalSettings retrieval;

    public HealthController(Settings settings, RetrievalSettings retrieval, ContextSettings context) {
        this.settings = settings;
        this.context = context;
        this.retrieval = retrieval;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok", settings.aiMode(), settings.vectorMode().equals("qdrant") ? "qdrant" : "local-keyword", settings.vectorMode().equals("qdrant") ? (retrieval.mode().equals("hybrid") ? "hybrid-rrf" : "dense") : "bm25", retrieval, true, settings.contextBudget(), context);
    }
}
