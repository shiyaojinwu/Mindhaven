package com.mindhaven.web.controller;

import com.mindhaven.application.community.PostService;
import com.mindhaven.application.dto.PostInput;
import com.mindhaven.domain.model.Models.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PostController {
  private final PostService service;

  public PostController(PostService service) {
    this.service = service;
  }

  @GetMapping("/posts")
  public Object posts() {
    return service.posts();
  }

  @PostMapping("/posts")
  public Object post(@Valid @RequestBody PostInput input) {
    return service.post(input);
  }

  @PostMapping("/posts/{id}/hug")
  public Object hug(@PathVariable String id) {
    return service.hug(id);
  }

  @DeleteMapping("/posts/{id}")
  public void delete(@PathVariable String id) {
    service.delete(id);
  }
}
