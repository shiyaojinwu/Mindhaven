package com.mindhaven.domain.port;

import com.mindhaven.domain.model.Models.Knowledge;
import java.util.List;

/** Infrastructure-neutral vector boundary. Tenant scope is mandatory for reads and writes. */
public interface KnowledgeVectorIndex {
  record Hit(String chunkId, double score) {}

  void index(String tenantId, List<Knowledge> documents);

  List<Hit> search(
      String tenantId, String query, String topic, String version, int limit, double threshold);
}
