package com.mindhaven.controller;

import com.mindhaven.model.dto.SurveyDraftInput;
import com.mindhaven.model.dto.SurveyRevisionRequest;
import com.mindhaven.model.dto.SurveySubmission;
import com.mindhaven.model.questionnaire.AnswerDraft;
import com.mindhaven.model.questionnaire.Assessment;
import com.mindhaven.model.questionnaire.SurveyDraft;
import com.mindhaven.model.questionnaire.SurveySnapshot;
import com.mindhaven.model.vo.TenantResponse;
import com.mindhaven.service.questionnaire.QuestionnaireService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class QuestionnaireController {
    private final QuestionnaireService service;

    public QuestionnaireController(QuestionnaireService service) {
        this.service = service;
    }

    @GetMapping("/surveys")
    public List<SurveySnapshot> published() {
        return service.published();
    }

    @GetMapping("/surveys/{id}/draft")
    public AnswerDraft answerDraft(@PathVariable String id, @RequestParam int version) {
        return service.answerDraft(id, version);
    }

    @PutMapping("/surveys/{id}/draft")
    public AnswerDraft saveAnswers(@PathVariable String id, @RequestBody SurveySubmission input) {
        return service.saveAnswers(id, input);
    }

    @PostMapping("/surveys/{id}/submit")
    public Assessment submit(@PathVariable String id, @RequestBody SurveySubmission input) {
        return service.submit(id, input);
    }

    @GetMapping("/admin/surveys")
    public List<SurveyDraft> drafts() {
        return service.drafts();
    }

    @PostMapping("/admin/surveys")
    public SurveyDraft create(@RequestBody SurveyDraftInput input) {
        return service.save(null, input);
    }

    @PutMapping("/admin/surveys/{id}")
    public SurveyDraft save(@PathVariable String id, @RequestBody SurveyDraftInput input) {
        return service.save(id, input);
    }


    @PostMapping("/admin/surveys/{id}/publish")
    public SurveyDraft publish(@PathVariable String id, @RequestBody SurveyRevisionRequest input) {
        return service.publish(id, input.expectedRevision(), false);
    }

    @PostMapping("/admin/surveys/{id}/archive")
    public SurveyDraft archive(@PathVariable String id, @RequestBody SurveyRevisionRequest input) {
        return service.publish(id, input.expectedRevision(), true);
    }

    @GetMapping("/admin/surveys/{id}/responses")
    public List<TenantResponse> responses(@PathVariable String id) {
        return service.responses(id);
    }
}
