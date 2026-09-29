package com.mindhaven.service.agent;

import com.mindhaven.model.ai.AgentTool;
import com.mindhaven.service.chat.ContextPlanner;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

public final class AgentMessageBudget {
    private AgentMessageBudget() { }

    public static int estimate(List<Message> messages, List<AgentTool> tools) {
        int total = 0;
        for (Message message : messages) {
            total += ContextPlanner.estimate(message.getText() == null ? "" : message.getText());
            if (message instanceof AssistantMessage assistant) {
                for (var call : assistant.getToolCalls()) total += ContextPlanner.estimate(call.id() + call.name() + call.arguments());
            } else if (message instanceof ToolResponseMessage response) {
                for (var tool : response.getResponses()) total += ContextPlanner.estimate(tool.id() + tool.name() + tool.responseData());
            }
        }
        for (var tool : tools) total += ContextPlanner.estimate(tool.name() + tool.description() + tool.inputSchema());
        return total;
    }
}
