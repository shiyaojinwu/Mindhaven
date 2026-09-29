package com.mindhaven.model.ai;

import org.springframework.ai.chat.messages.AssistantMessage;

public record AgentStep(AssistantMessage message, String finishReason, Integer promptTokens, Integer completionTokens) { }
