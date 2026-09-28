package com.mindhaven.model.questionnaire;

import java.util.List;

public record Question(String id, String title, String type, boolean required, List<Option> options) {
}
