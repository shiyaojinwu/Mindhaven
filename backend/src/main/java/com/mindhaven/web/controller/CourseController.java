package com.mindhaven.web.controller;

import com.mindhaven.application.course.CourseService;
import com.mindhaven.application.dto.CourseDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/courses")
public class CourseController {
  private final CourseService service;

  public CourseController(CourseService service) {
    this.service = service;
  }

  @GetMapping
  public List<Draft> list() {
    return service.list();
  }

  @PostMapping
  public Draft create(@Valid @RequestBody Input input) {
    return service.create(input);
  }

  @PutMapping("/{id}")
  public Draft save(@PathVariable String id, @Valid @RequestBody Input input) {
    return service.save(id, input);
  }

  @PostMapping("/{id}/publish")
  public Draft publish(@PathVariable String id, @RequestBody Revision input) {
    return service.publish(id, input);
  }

  @PostMapping("/{id}/archive")
  public Draft archive(@PathVariable String id, @RequestBody Revision input) {
    return service.archive(id, input);
  }
}
