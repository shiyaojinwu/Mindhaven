package com.mindhaven.integration.ai;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.Consumer;

public interface AiGateway {
    record Result(String text, Integer promptTokens, Integer completionTokens) {
    }

    Result complete(List<Message> messages, int maxTokens);

    Result stream(List<Message> messages, int maxTokens, Consumer<String> onDelta);
}
