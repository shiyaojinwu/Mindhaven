package com.mindhaven.model.chat;

import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.Metrics;

import java.util.*;

public record TurnResult(ChatMessage message, Metrics metrics) {
}
