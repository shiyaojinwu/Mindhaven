package com.mindhaven.model.vo;

import com.mindhaven.config.RetrievalSettings;
import com.mindhaven.config.ContextSettings;

public record HealthResponse(String status, String mode, String retrieval, String retrievalStrategy,
                             RetrievalSettings retrievalConfig, boolean multiTenant, int contextWindow, ContextSettings contextConfig) {
}
