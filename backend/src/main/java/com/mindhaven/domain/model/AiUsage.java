package com.mindhaven.domain.model;

public record AiUsage(
    String id,
    String runId,
    String purpose,
    String model,
    String promptHash,
    String status,
    String source,
    Integer inputTokens,
    Integer outputTokens,
    int estimatedInput,
    int estimatedOutput,
    long durationMs,
    String createdAt) {}
