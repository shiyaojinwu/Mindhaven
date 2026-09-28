package com.mindhaven.controller;

import com.mindhaven.model.dto.KnowledgeImportRequest;
import com.mindhaven.model.knowledge.Knowledge;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.knowledge.KnowledgeService;
import com.mindhaven.service.knowledge.KnowledgeIndexService;
import com.mindhaven.model.vo.KnowledgeIndexResult;
import com.mindhaven.model.vo.KnowledgeIndexStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    private final KnowledgeIndexService index;

    public KnowledgeController(KnowledgeService knowledge, KnowledgeIndexService index) {
        this.index = index;
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

    @GetMapping("/knowledge/index/status")
    public KnowledgeIndexStatus indexStatus() {
        TenantContext.requireAdmin();
        return index.status();
    }
    @PostMapping("/knowledge/index")
    public KnowledgeIndexResult index(@RequestParam(defaultValue = "false") boolean force) {
        TenantContext.requireAdmin();
        return index.sync(force);
    }
}
