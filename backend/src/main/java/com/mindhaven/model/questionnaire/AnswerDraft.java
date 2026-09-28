package com.mindhaven.model.questionnaire;

import com.mindhaven.model.dto.AnswerInput;

import java.util.*;

public record AnswerDraft(int version, List<AnswerInput> answers, String updatedAt) {
}
