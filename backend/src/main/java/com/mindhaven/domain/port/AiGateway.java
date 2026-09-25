package com.mindhaven.domain.port;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.Message;

public interface AiGateway {
  record Result(String text, Integer promptTokens, Integer completionTokens) {}

  Result complete(List<Message> messages, int maxTokens);

  Result stream(List<Message> messages, int maxTokens, Consumer<String> onDelta);
}
