package com.mindhaven.application.knowledge;

import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.RecordStore;
import com.mindhaven.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService {
  private final RecordStore store;
  private final ObjectProvider<VectorStore> vectors;

  public KnowledgeService(RecordStore store, ObjectProvider<VectorStore> vectors) {
    this.store = store;
    this.vectors = vectors;
  }

  public List<Knowledge> all() {
    return store.list("knowledge", Knowledge.class);
  }

  public Knowledge get(String id) {
    return store
        .get("knowledge", id, Knowledge.class)
        .orElseThrow(() -> new NoSuchElementException("知识片段不存在"));
  }

  private String vectorId(String id) {
    return UUID.nameUUIDFromBytes(
            (TenantContext.require().tenantId() + ":" + id).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  public synchronized int index() {
    VectorStore v = vectors.getIfAvailable();
    if (v == null) throw new IllegalArgumentException("当前为本地关键词检索，无需向量索引");
    List<Document> docs =
        all().stream()
            .map(
                k ->
                    Document.builder()
                        .id(vectorId(k.id()))
                        .text(k.text())
                        .metadata(
                            Map.of(
                                "tenantId",
                                TenantContext.require().tenantId(),
                                "chunkId",
                                k.id(),
                                "topic",
                                k.topic(),
                                "version",
                                k.version(),
                                "title",
                                k.title()))
                        .build())
            .toList();
    v.add(docs);
    return docs.size();
  }

  public synchronized Knowledge add(
      String title, String topic, String version, String url, String text) {
    // Immutable chunks: updates become a new ID and can be pinned by version.
    Knowledge k = new Knowledge(UUID.randomUUID().toString(), title, topic, version, url, text);
    store.put("knowledge", k.id(), k);
    return k;
  }

  public List<Citation> search(String query, String topic, String version, int k) {
    VectorStore v = vectors.getIfAvailable();
    if (v != null) {
      var b = new FilterExpressionBuilder();
      var filter =
          b.and(b.eq("tenantId", TenantContext.require().tenantId()), b.eq("version", version));
      if (!topic.equals("全部")) filter = b.and(filter, b.eq("topic", topic));
      return v
          .similaritySearch(
              SearchRequest.builder()
                  .query(query)
                  .topK(k)
                  .similarityThreshold(0.35)
                  .filterExpression(filter.build())
                  .build())
          .stream()
          .filter(d -> TenantContext.require().tenantId().equals(d.getMetadata().get("tenantId")))
          .map(
              d -> {
                Knowledge source = get(String.valueOf(d.getMetadata().get("chunkId")));
                return citation(source, d.getScore() == null ? 0 : d.getScore());
              })
          .toList();
    }
    return all().stream()
        .filter(x -> x.version().equals(version) && (topic.equals("全部") || topic.equals(x.topic())))
        .map(x -> citation(x, score(query, x.title() + x.topic() + x.text())))
        .filter(x -> x.score() > 0)
        .sorted(Comparator.comparingDouble(Citation::score).reversed())
        .limit(k)
        .toList();
  }

  static double score(String query, String document) {
    Set<String> terms = new HashSet<>();
    String clean = query.replaceAll("[\\s\\p{Punct}，。？！]", "");
    for (int i = 0; i < clean.length() - 1; i++) terms.add(clean.substring(i, i + 2));
    terms.removeAll(
        Set.of(
            "什么", "怎么", "如何", "可以", "这个", "那个", "一下", "是什", "为什", "些什", "么办", "情况", "这种", "那我",
            "先做"));
    if (terms.isEmpty()) return 0;
    return terms.stream().filter(document::contains).count() / (double) terms.size();
  }

  static Citation citation(Knowledge k, double score) {
    return new Citation(k.id(), k.title(), k.topic(), k.version(), k.sourceUrl(), k.text(), score);
  }
}
