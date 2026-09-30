package com.mindhaven.model.chat;

public record RunNotification(String id, String runId, String eventType, String payload, String terminalPayload) { }
