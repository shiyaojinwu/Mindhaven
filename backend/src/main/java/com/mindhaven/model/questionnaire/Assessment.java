package com.mindhaven.model.questionnaire;

import java.util.List;

public record Assessment(String id, String surveyId, int surveyVersion, String surveyTitle,
                         List<AnswerSnapshot> answers, int score, int maxScore, String label, String report,
                         String createdAt) {
}
