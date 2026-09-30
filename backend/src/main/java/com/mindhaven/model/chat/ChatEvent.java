package com.mindhaven.model.chat;

import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.model.ai.Recommendation;

import java.util.List;

/** Internal events; the runtime maps them to the existing SSE wire protocol. */
public sealed interface ChatEvent {
    record AnswerReset() implements ChatEvent { }
    record Delta(String text) implements ChatEvent { }
    record Sources(List<Citation> citations) implements ChatEvent {
        public Sources {
            citations = List.copyOf(citations);
        }
    }
    record AgentStatus(String phase, String label, int step, String toolName, String toolCallId,
                       String arguments, String draft) implements ChatEvent {
        public AgentStatus(String phase, String label, int step, String toolName, String toolCallId,
                           String arguments) {
            this(phase, label, step, toolName, toolCallId, arguments, null);
        }
        public AgentStatus(String phase, String label, int step) {
            this(phase, label, step, null, null, null);
        }
    }
    record Recommendations(List<Recommendation> items) implements ChatEvent {
        public Recommendations { items = List.copyOf(items); }
    }
    record Done(TurnResult result) implements ChatEvent { }
}
