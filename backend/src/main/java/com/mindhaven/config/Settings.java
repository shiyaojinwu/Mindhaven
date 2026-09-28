package com.mindhaven.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mindhaven")
public record Settings(String aiMode, String vectorMode, String chatBaseUrl, String chatPath, String chatModel,
                       String chatKey, String embeddingBaseUrl, String embeddingPath, String embeddingModel,
                       String embeddingKey, String qdrantHost, int qdrantPort, String qdrantCollection,
                       int contextBudget, int outputBudget, int knowledgeBudget) {
}
