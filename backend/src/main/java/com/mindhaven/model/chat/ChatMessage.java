package com.mindhaven.model.chat;

import com.mindhaven.model.knowledge.Citation;

import java.util.List;

public record ChatMessage(String id, String sessionId, long seq, String role, String content, String createdAt,
                      List<Citation> citations, String status, CitationCheck citationCheck) {
    public ChatMessage(String id, String sessionId, long seq, String role, String content, String createdAt, List<Citation> citations, String status) {
        this(id, sessionId, seq, role, content, createdAt, citations, status, null);
    }
}
