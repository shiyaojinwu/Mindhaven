package com.mindhaven.model.dto;

import com.mindhaven.model.dto.AnswerInput;

import java.util.*;

public record SurveySubmission(int version, List<AnswerInput> answers) {
}
