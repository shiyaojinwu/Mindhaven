package com.mindhaven.model.vo;

import com.mindhaven.model.questionnaire.Assessment;

public record TenantResponse(String id, String userId, String username, Assessment assessment) {
}
