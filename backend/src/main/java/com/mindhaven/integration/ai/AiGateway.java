package com.mindhaven.integration.ai;

import org.springframework.ai.chat.messages.Message;
import com.mindhaven.model.ai.AgentStep;
import com.mindhaven.model.ai.AgentTool;

import java.util.List;
import java.util.function.Consumer;

public interface AiGateway {
    record Result(String text, Integer promptTokens, Integer completionTokens) {
    }

    default AgentStep step(List<Message> messages, int maxTokens, List<AgentTool> tools) {
        throw new UnsupportedOperationException("This model adapter does not support tool calling");
    }

    default AgentStep streamStep(List<Message> messages, int maxTokens, List<AgentTool> tools, Consumer<String> delta) {
        AgentStep result = step(messages, maxTokens, tools);
        if (!result.message().hasToolCalls() && result.message().getText() != null) delta.accept(result.message().getText());
        return result;
    }

    Result complete(List<Message> messages, int maxTokens);

    Result stream(List<Message> messages, int maxTokens, Consumer<String> onDelta);
}
