package com.mindhaven.controller;

import com.mindhaven.model.course.Course;
import com.mindhaven.model.course.Progress;
import com.mindhaven.model.questionnaire.Assessment;
import com.mindhaven.service.course.LearningService;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api")
public class LearningController {
    private final LearningService service;

    public LearningController(LearningService service) {
        this.service = service;
    }

    @GetMapping("/courses")
    public List<Course> courses() {
        return service.courses();
    }

    @GetMapping("/progress")
    public List<Progress> progress() {
        return service.progress();
    }

    @PostMapping("/courses/{id}/complete")
    public Progress complete(@PathVariable String id) {
        return service.complete(id);
    }

    @GetMapping("/reports")
    public List<Assessment> reports() {
        return service.reports();
    }
}
