package com.mindhaven.model.chat;

import com.mindhaven.model.knowledge.RetrievalResult;

import java.util.List;

public record Metrics(String id, String sessionId, String mode, String model, String promptVersion,
                      String knowledgeVersion, String rewrittenQuery, int retrievedCount, List<String> retrievedIds,
                      int contextEstimate, Integer promptTokens, Integer completionTokens, long firstTokenMs,
                      long totalMs, int summaryVersion, long coveredThroughSeq, boolean citationIdsValid,
                      String createdAt, CitationCheck citationCheck, String retrievalMode,
                      List<RetrievalResult.Match> retrievalMatches, List<String> contextIds,
                      RetrievalResult.Configuration retrievalConfig) {
}
