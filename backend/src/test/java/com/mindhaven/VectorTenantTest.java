package com.mindhaven;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindhaven.domain.model.Models.Knowledge;
import com.mindhaven.infrastructure.ai.QdrantKnowledgeIndex;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;

class VectorTenantTest {
  private Document document(String tenant, String topic, String version, String chunk) {
    return Document.builder()
        .text("payload")
        .metadata(Map.of("tenantId", tenant, "topic", topic, "version", version, "chunkId", chunk))
        .build();
  }

  @Test
  @SuppressWarnings("unchecked")
  void tenantTopicVersionAreRequiredInFilterAndReturnedPayload() {
    var vector = mock(VectorStore.class);
    var index = new QdrantKnowledgeIndex(vector);
    when(vector.similaritySearch(any(SearchRequest.class)))
        .thenReturn(
            List.of(
                document("tenant-b", "topic", "v1", "foreign"),
                document("tenant-a", "other", "v1", "wrong-topic"),
                document("tenant-a", "topic", "v2", "wrong-version"),
                document("tenant-a", "topic", "v1", "local")));
    assertThat(index.search("tenant-a", "query", "topic", "v1", 20, .35))
        .extracting(h -> h.chunkId())
        .containsExactly("local");
    var request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vector).similaritySearch(request.capture());
    assertThat(request.getValue().getFilterExpression().toString())
        .contains("tenantId", "tenant-a", "topic", "v1");
    assertThat(request.getValue().getTopK()).isEqualTo(20);
    assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(.35);
    var chunk = new Knowledge("shared", "Title", "topic", "v1", "", "content");
    index.index("tenant-a", List.of(chunk));
    index.index("tenant-b", List.of(chunk));
    index.index("tenant-a", List.of(chunk));
    ArgumentCaptor<List<Document>> docs = ArgumentCaptor.forClass(List.class);
    verify(vector, times(3)).add(docs.capture());
    var first = docs.getAllValues().get(0).getFirst();
    var second = docs.getAllValues().get(1).getFirst();
    assertThat(first.getId())
        .isNotEqualTo(second.getId())
        .isEqualTo(docs.getAllValues().get(2).getFirst().getId());
    assertThat(first.getMetadata())
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("version", "v1");
    assertThat(second.getMetadata()).containsEntry("tenantId", "tenant-b");
  }
}
