package com.mindhaven.model.questionnaire;

import java.util.List;

public record Survey(String id, String title, String description, List<String> questions) {
}
