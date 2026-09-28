package com.mindhaven.infrastructure.ai;

import com.mindhaven.domain.model.Models.Knowledge;
import com.mindhaven.domain.port.KnowledgeVectorIndex;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mindhaven.vector-mode", havingValue = "qdrant")
public class QdrantKnowledgeIndex implements KnowledgeVectorIndex {
  private final VectorStore vectors;

  public QdrantKnowledgeIndex(VectorStore vectors) {
    this.vectors = vectors;
  }

  public void index(String tenantId, List<Knowledge> documents) {
    if (documents.isEmpty()) return;
    vectors.add(
        documents.stream()
            .map(
                k ->
                    Document.builder()
                        .id(
                            UUID.nameUUIDFromBytes(
                                    (tenantId + ":" + k.id()).getBytes(StandardCharsets.UTF_8))
                                .toString())
                        .text(k.text())
                        .metadata(
                            Map.of(
                                "tenantId",
                                tenantId,
                                "chunkId",
                                k.id(),
                                "topic",
                                k.topic(),
                                "version",
                                k.version(),
                                "title",
                                k.title()))
                        .build())
            .toList());
  }

  public List<Hit> search(
      String tenantId, String query, String topic, String version, int limit, double threshold) {
    var b = new FilterExpressionBuilder();
    var filter = b.and(b.eq("tenantId", tenantId), b.eq("version", version));
    if (!topic.equals("全部")) filter = b.and(filter, b.eq("topic", topic));
    return vectors
        .similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(limit)
                .similarityThreshold(threshold)
                .filterExpression(filter.build())
                .build())
        .stream()
        .filter(d -> tenantId.equals(d.getMetadata().get("tenantId")))
        .filter(d -> version.equals(d.getMetadata().get("version")))
        .filter(d -> topic.equals("全部") || topic.equals(d.getMetadata().get("topic")))
        .filter(d -> d.getMetadata().get("chunkId") instanceof String)
        .map(
            d ->
                new Hit(
                    (String) d.getMetadata().get("chunkId"),
                    d.getScore() == null ? 0 : d.getScore()))
        .toList();
  }
}
