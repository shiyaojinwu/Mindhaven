package com.mindhaven.controller;

import com.mindhaven.model.report.ReportAnalysis;
import com.mindhaven.model.vo.ReportAnalysisResponse;
import com.mindhaven.service.report.ReportService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/{id}/analysis")
    public ReportAnalysisResponse read(@PathVariable String id) {
        return service.read(id);
    }

    @PostMapping("/{id}/analysis")
    public ReportAnalysis analyze(@PathVariable String id) {
        return service.analyze(id);
    }
}
