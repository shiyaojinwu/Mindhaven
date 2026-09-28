package com.mindhaven;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindhaven.application.ai.AiOperations;
import com.mindhaven.application.knowledge.*;
import com.mindhaven.config.RetrievalSettings;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.*;
import com.mindhaven.security.TenantContext;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;

class KnowledgeRetrievalTest {
  private final LexicalRetriever lexical = new LexicalRetriever();
  private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

  private Citation c(String id, double score) {
    return new Citation(id, id, "睡眠", "v1", "", "text", score);
  }

  private Knowledge k(String id, String topic, String version, String text) {
    return new Knowledge(id, id, topic, version, "", text);
  }

  @Test
  void rrfUsesRankNotIncompatibleRawScoresAndDeduplicates() {
    var result =
        fusion.fuse(
            List.of(c("vector-only", .99), c("both", .4), c("both", .4)),
            List.of(c("lexical-only", 99999), c("both", 1)),
            60,
            4);
    assertThat(result.citations())
        .extracting(Citation::id)
        .containsExactly("both", "lexical-only", "vector-only");
    assertThat(result.matches().getFirst().rrfScore()).isEqualTo(2.0 / 62);
    assertThat(result.matches().getFirst().vectorRank()).isEqualTo(2);
    assertThat(result.matches().getFirst().lexicalRank()).isEqualTo(2);
    assertThat(fusion.fuse(List.of(), List.of(c("a", 1)), 60, 4).citations()).hasSize(1);
    assertThat(fusion.fuse(List.of(), List.of(), 60, 4).citations()).isEmpty();
  }

  @Test
  void bm25FindsChinesePhrasesAndExactIdentifiersButNotUnrelatedQueries() {
    var corpus =
        List.of(k("sleep-v1", "睡眠", "v1", "睡前放松，规律睡眠"), k("stress-v1", "压力", "v1", "考试学习压力"));
    assertThat(lexical.search("睡前很紧张", corpus, 4))
        .extracting(Citation::id)
        .containsExactly("sleep-v1");
    assertThat(lexical.search("SLEEP-V1", corpus, 4))
        .extracting(Citation::id)
        .containsExactly("sleep-v1");
    assertThat(lexical.search("量子纠缠火星轨道", corpus, 4)).isEmpty();
    assertThat(lexical.search("怎么如何", corpus, 4)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void bothChannelsRespectCanonicalVersionAndTopicAndKeepLexicalOnlyHits() {
    var store = mock(RecordStore.class);
    var vector = mock(KnowledgeVectorIndex.class);
    ObjectProvider<KnowledgeVectorIndex> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(vector);
    var ai = mock(AiOperations.class);
    when(ai.embedding(anyString(), anyString(), any()))
        .thenAnswer(i -> ((java.util.function.Supplier<?>) i.getArgument(2)).get());
    when(store.list("knowledge", Knowledge.class))
        .thenReturn(
            List.of(
                k("dense", "睡眠", "v1", "保持规律作息"), k("lexical", "睡眠", "v1", "睡前可以放松"),
                k("old", "睡眠", "v0", "睡前放松"), k("other", "压力", "v1", "睡前放松")));
    when(vector.search("t", "睡前", "睡眠", "v1", 20, .35))
        .thenReturn(
            List.of(
                new KnowledgeVectorIndex.Hit("dense", .9), new KnowledgeVectorIndex.Hit("old", .8),
                new KnowledgeVectorIndex.Hit("other", .7),
                    new KnowledgeVectorIndex.Hit("stale", .6)));
    var service =
        new KnowledgeService(
            store, provider, ai, lexical, fusion, new RetrievalSettings("hybrid", 20, 60, .35));
    try (var scope =
        TenantContext.open(new TenantContext.Identity("t", "t", "T", "u", "u", "ADMIN"))) {
      var result = service.retrieve("睡前", "睡眠", "v1", 4);
      assertThat(result.mode()).isEqualTo("hybrid-rrf");
      assertThat(result.citations()).extracting(Citation::id).containsExactly("dense", "lexical");
      assertThat(result.matches().getLast().vectorRank()).isNull();
      assertThat(result.matches().getLast().lexicalRank()).isEqualTo(1);
      assertThat(service.retrieve("睡前", "睡眠", "v99", 4).citations()).isEmpty();
      verify(vector, times(1))
          .search(anyString(), anyString(), anyString(), anyString(), anyInt(), anyDouble());
      when(vector.search("t", "睡前", "睡眠", "v1", 20, .35))
          .thenThrow(new IllegalStateException("unavailable"));
      assertThatThrownBy(() -> service.retrieve("睡前", "睡眠", "v1", 4)).hasMessage("unavailable");
      when(provider.getIfAvailable()).thenReturn(null);
      assertThat(service.retrieve("睡前", "睡眠", "v1", 4).mode()).isEqualTo("bm25");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void denseModeDoesNotMergeLexicalHits() {
    var store = mock(RecordStore.class);
    when(store.list("knowledge", Knowledge.class)).thenReturn(List.of(k("a", "睡眠", "v1", "睡前放松")));
    ObjectProvider<KnowledgeVectorIndex> provider = mock(ObjectProvider.class);
    var vector = mock(KnowledgeVectorIndex.class);
    when(provider.getIfAvailable()).thenReturn(vector);
    var ai = mock(AiOperations.class);
    when(ai.embedding(anyString(), anyString(), any()))
        .thenAnswer(i -> ((java.util.function.Supplier<?>) i.getArgument(2)).get());
    when(vector.search(anyString(), anyString(), anyString(), anyString(), anyInt(), anyDouble()))
        .thenReturn(List.of());
    var service =
        new KnowledgeService(
            store, provider, ai, lexical, fusion, new RetrievalSettings("dense", 20, 60, .35));
    try (var scope =
        TenantContext.open(new TenantContext.Identity("t", "t", "T", "u", "u", "ADMIN"))) {
      var result = service.retrieve("睡前", "睡眠", "v1", 4);
      assertThat(result.mode()).isEqualTo("dense");
      assertThat(result.citations()).isEmpty();
      verify(vector).search("t", "睡前", "睡眠", "v1", 4, .35);
    }
  }
}
