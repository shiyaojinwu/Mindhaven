package com.mindhaven.model.chat;

import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.model.ai.Recommendation;

import java.util.List;

public record ChatMessage(String id, String sessionId, long seq, String role, String content, String createdAt,
                      List<Citation> citations, String status, CitationCheck citationCheck, List<Recommendation> recommendations) {
    public ChatMessage {
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
    public ChatMessage(String id, String sessionId, long seq, String role, String content, String createdAt,
                       List<Citation> citations, String status, CitationCheck citationCheck) {
        this(id, sessionId, seq, role, content, createdAt, citations, status, citationCheck, List.of());
    }
    public ChatMessage(String id, String sessionId, long seq, String role, String content, String createdAt, List<Citation> citations, String status) {
        this(id, sessionId, seq, role, content, createdAt, citations, status, null);
    }
}
