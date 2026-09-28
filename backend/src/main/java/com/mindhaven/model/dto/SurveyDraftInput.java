package com.mindhaven.model.dto;

import com.mindhaven.model.questionnaire.Question;

import java.util.*;

public record SurveyDraftInput(String title, String description, List<Question> questions, int expectedRevision) {
}
