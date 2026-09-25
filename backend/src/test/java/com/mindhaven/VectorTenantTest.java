package com.mindhaven;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindhaven.application.knowledge.KnowledgeService;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.RecordStore;
import com.mindhaven.security.TenantContext;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.beans.factory.ObjectProvider;

class VectorTenantTest {
  @Test
  @SuppressWarnings("unchecked")
  void tenantIsRequiredInVectorFilterPayloadAndIds() {
    var store = mock(RecordStore.class);
    var vector = mock(VectorStore.class);
    ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(vector);
    var chunk = new Knowledge("shared-chunk", "Title", "topic", "v1", "", "content");
    when(store.list("knowledge", Knowledge.class)).thenReturn(List.of(chunk));
    when(store.get("knowledge", "shared-chunk", Knowledge.class)).thenReturn(Optional.of(chunk));
    var service = new KnowledgeService(store, provider);
    var a = new TenantContext.Identity("tenant-a", "a", "A", "user-a", "admin", "ADMIN");
    var b = new TenantContext.Identity("tenant-b", "b", "B", "user-b", "admin", "ADMIN");
    when(vector.similaritySearch(any(SearchRequest.class)))
        .thenReturn(
            List.of(
                Document.builder()
                    .text("foreign")
                    .metadata(Map.of("tenantId", "tenant-b", "chunkId", "shared-chunk"))
                    .build(),
                Document.builder()
                    .text("local")
                    .metadata(Map.of("tenantId", "tenant-a", "chunkId", "shared-chunk"))
                    .build()));
    try (var scope = TenantContext.open(a)) {
      service.index();
      assertThat(service.search("query", "topic", "v1", 4)).hasSize(1);
    }
    try (var scope = TenantContext.open(b)) {
      service.index();
    }
    var request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vector).similaritySearch(request.capture());
    String expression = request.getValue().getFilterExpression().toString();
    assertThat(expression).contains("tenantId", "tenant-a", "topic", "v1");
    ArgumentCaptor<List<Document>> docs = ArgumentCaptor.forClass(List.class);
    verify(vector, times(2)).add(docs.capture());
    var first = docs.getAllValues().getFirst().getFirst();
    var second = docs.getAllValues().getLast().getFirst();
    assertThat(first.getId()).isNotEqualTo(second.getId());
    assertThat(first.getMetadata()).containsEntry("tenantId", "tenant-a");
    assertThat(second.getMetadata()).containsEntry("tenantId", "tenant-b");
    assertThatThrownBy(TenantContext::require).isInstanceOf(HttpProblem.class);
  }
}
