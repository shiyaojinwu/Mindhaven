package com.mindhaven.controller;

import com.mindhaven.model.community.Post;
import com.mindhaven.model.dto.PostInput;
import com.mindhaven.service.community.PostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api")
public class PostController {
    private final PostService service;

    public PostController(PostService service) {
        this.service = service;
    }

    @GetMapping("/posts")
    public List<Post> posts() {
        return service.posts();
    }

    @PostMapping("/posts")
    public Post post(@Valid @RequestBody PostInput input) {
        return service.post(input);
    }

    @PostMapping("/posts/{id}/hug")
    public Post hug(@PathVariable String id) {
        return service.hug(id);
    }

    @DeleteMapping("/posts/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
