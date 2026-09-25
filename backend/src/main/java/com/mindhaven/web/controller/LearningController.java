package com.mindhaven.web.controller;

import com.mindhaven.application.course.LearningService;
import com.mindhaven.domain.model.Models.*;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class LearningController {
  private final LearningService service;

  public LearningController(LearningService service) {
    this.service = service;
  }

  @GetMapping("/courses")
  public Object courses() {
    return service.courses();
  }

  @GetMapping("/progress")
  public Object progress() {
    return service.progress();
  }

  @PostMapping("/courses/{id}/complete")
  public Object complete(@PathVariable String id) {
    return service.complete(id);
  }

  @GetMapping("/reports")
  public Object reports() {
    return service.reports();
  }
}
