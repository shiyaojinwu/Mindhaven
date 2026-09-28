package com.mindhaven.model.chat;

import com.mindhaven.model.knowledge.Citation;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public record ContextPlan(List<Message> messages, List<Citation> citations, int estimate) {
}
