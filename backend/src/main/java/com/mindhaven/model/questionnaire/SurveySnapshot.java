package com.mindhaven.model.questionnaire;

import java.util.List;

public record SurveySnapshot(String id, String surveyId, String title, String description, int version,
                             List<Question> questions, String publishedAt) {
}
