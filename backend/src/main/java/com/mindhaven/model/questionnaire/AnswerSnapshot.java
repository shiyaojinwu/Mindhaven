package com.mindhaven.model.questionnaire;

import java.util.List;

public record AnswerSnapshot(String questionId, String title, String type, List<String> selectedLabels, String text,
                             int score) {
}
