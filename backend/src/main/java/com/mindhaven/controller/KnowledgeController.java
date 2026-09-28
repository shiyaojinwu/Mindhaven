package com.mindhaven.controller;

import com.mindhaven.model.dto.KnowledgeImportRequest;
import com.mindhaven.model.knowledge.Knowledge;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.knowledge.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api")
public class KnowledgeController {
    private final KnowledgeService knowledge;

    public KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/knowledge")
    public List<Knowledge> knowledge() {
        return knowledge.all();
    }

    @GetMapping("/knowledge/{id}")
    public Knowledge source(@PathVariable String id) {
        return knowledge.get(id);
    }


    @PostMapping("/knowledge")
    public Knowledge add(@Valid @RequestBody KnowledgeImportRequest i) {
        TenantContext.requireAdmin();
        return knowledge.add(i.title(), i.topic(), i.version(), i.sourceUrl() == null ? "" : i.sourceUrl(), i.text());
    }

    @PostMapping("/knowledge/index")
    public Map<String, Integer> index() {
        TenantContext.requireAdmin();
        return Map.of("indexed", knowledge.index());
    }
}
