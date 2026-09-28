package com.mindhaven.model.questionnaire;

import java.util.List;

public record SurveyDraft(String id, String title, String description, List<Question> questions, int revision,
                          int publishedVersion, int publishedRevision, String status, String updatedAt) {
}
