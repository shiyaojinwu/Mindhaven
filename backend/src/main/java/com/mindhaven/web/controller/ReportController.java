package com.mindhaven.web.controller;

import com.mindhaven.application.report.ReportService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
  private final ReportService service;

  public ReportController(ReportService service) {
    this.service = service;
  }

  @GetMapping("/{id}/analysis")
  public Object read(@PathVariable String id) {
    return service.read(id);
  }

  @PostMapping("/{id}/analysis")
  public Object analyze(@PathVariable String id) {
    return service.analyze(id);
  }
}
