package com.mindhaven.model.chat;

import com.mindhaven.model.knowledge.Citation;

import java.util.List;

/** Internal events; the runtime maps them to the existing SSE wire protocol. */
public sealed interface ChatEvent {
    record Delta(String text) implements ChatEvent { }
    record Sources(List<Citation> citations) implements ChatEvent {
        public Sources {
            citations = List.copyOf(citations);
        }
    }
    record Done(TurnResult result) implements ChatEvent { }
}
