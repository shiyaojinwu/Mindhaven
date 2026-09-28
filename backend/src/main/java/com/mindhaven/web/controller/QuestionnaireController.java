package com.mindhaven.web.controller;

import com.mindhaven.application.questionnaire.QuestionnaireService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class QuestionnaireController {
  private final QuestionnaireService service;

  public QuestionnaireController(QuestionnaireService service) {
    this.service = service;
  }

  @GetMapping("/surveys")
  public Object published() {
    return service.published();
  }

  @GetMapping("/surveys/{id}/draft")
  public Object answerDraft(@PathVariable String id, @RequestParam int version) {
    return service.answerDraft(id, version);
  }

  @PutMapping("/surveys/{id}/draft")
  public Object saveAnswers(
      @PathVariable String id, @RequestBody QuestionnaireService.Submission input) {
    return service.saveAnswers(id, input);
  }

  @PostMapping("/surveys/{id}/submit")
  public Object submit(
      @PathVariable String id, @RequestBody QuestionnaireService.Submission input) {
    return service.submit(id, input);
  }

  @GetMapping("/admin/surveys")
  public Object drafts() {
    return service.drafts();
  }

  @PostMapping("/admin/surveys")
  public Object create(@RequestBody QuestionnaireService.DraftInput input) {
    return service.save(null, input);
  }

  @PutMapping("/admin/surveys/{id}")
  public Object save(@PathVariable String id, @RequestBody QuestionnaireService.DraftInput input) {
    return service.save(id, input);
  }

  public record Revision(int expectedRevision) {}

  @PostMapping("/admin/surveys/{id}/publish")
  public Object publish(@PathVariable String id, @RequestBody Revision input) {
    return service.publish(id, input.expectedRevision(), false);
  }

  @PostMapping("/admin/surveys/{id}/archive")
  public Object archive(@PathVariable String id, @RequestBody Revision input) {
    return service.publish(id, input.expectedRevision(), true);
  }

  @GetMapping("/admin/surveys/{id}/responses")
  public Object responses(@PathVariable String id) {
    return service.responses(id);
  }
}
