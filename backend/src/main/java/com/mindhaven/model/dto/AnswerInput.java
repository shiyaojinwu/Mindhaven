package com.mindhaven.model.dto;

import java.util.List;

public record AnswerInput(String questionId, List<String> optionIds, String text) {
}
