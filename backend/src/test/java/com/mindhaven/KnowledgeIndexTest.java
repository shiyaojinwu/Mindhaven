package com.mindhaven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.vector.KnowledgeVectorIndex;
import com.mindhaven.manager.AuthManager;
import com.mindhaven.manager.KnowledgeIndexManager;
import com.mindhaven.manager.KnowledgeManager;
import com.mindhaven.model.knowledge.Knowledge;
import com.mindhaven.security.LoginIdentity;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.AiOperations;
import com.mindhaven.service.knowledge.KnowledgeIndexService;
import com.mindhaven.service.knowledge.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.mindhaven.common.error.HttpProblem;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "mindhaven.ai-mode=demo", "mindhaven.vector-mode=local"})
class KnowledgeIndexTest {
    @Autowired KnowledgeIndexService index;
    @Autowired KnowledgeService service;
    @Autowired KnowledgeManager knowledge;
    @Autowired KnowledgeIndexManager states;
    @Autowired AuthManager auth;
    @Autowired ObjectMapper json;
    @Autowired Settings settings;
    @Autowired AiOperations ai;
    @Autowired ObjectProvider<KnowledgeVectorIndex> vectors;
    @MockitoBean KnowledgeVectorIndex vector;

    private LoginIdentity identity() {
        String id = UUID.randomUUID().toString();
        auth.createTenant(id, id, "test", "2026-01-01");
        return new LoginIdentity(id, id, "test", "owner", "owner", "ADMIN");
    }

    private Knowledge document(String id) {
        return new Knowledge(id, "title", "睡眠", "v1", "", "规律作息");
    }

    @Test
    void unchangedChunksSkipEmbeddingAndMetadataChangesInvalidateTheFingerprint() {
        var owner = identity();
        try (var scope = TenantContext.open(owner)) {
            knowledge.save(document("a"));
            assertThat(index.status().pending()).isEqualTo(1);
            assertThat(index.sync(false).indexed()).isEqualTo(1);
            assertThat(index.sync(false).skipped()).isEqualTo(1);
            verify(vector, times(1)).index(eq(owner.tenantId()), anyList());
            knowledge.save(new Knowledge("a", "updated title", "睡眠", "v1", "", "规律作息"));
            assertThat(index.status().pending()).isEqualTo(1);
            assertThat(index.sync(false).indexed()).isEqualTo(1);
            assertThat(index.status().indexed()).isEqualTo(1);
            assertThat(index.sync(true).indexed()).isEqualTo(1);
            verify(vector, times(3)).index(eq(owner.tenantId()), anyList());
        }
    }

    @Test
    void failedWriteIsRetryableAndCompletedChunksAreNotRepeated() {
        var owner = identity();
        try (var scope = TenantContext.open(owner)) {
            knowledge.save(document("a"));
            knowledge.save(document("b"));
            doNothing().doThrow(new IllegalStateException("provider secret must not escape"))
                    .when(vector).index(eq(owner.tenantId()), anyList());
            var failed = index.sync(false);
            assertThat(failed.indexed()).isEqualTo(1);
            assertThat(failed.failed()).isEqualTo(1);
            assertThat(index.status().failed()).isEqualTo(1);
            assertThat(index.status().running()).isFalse();
            doNothing().when(vector).index(eq(owner.tenantId()), anyList());
            assertThat(index.sync(false).indexed()).isEqualTo(1);
            assertThat(index.status().indexed()).isEqualTo(2);
            assertThat(index.status().failed()).isZero();
            verify(vector, times(3)).index(eq(owner.tenantId()), anyList());
        }
    }

    @Test
    void tenantsAndEmbeddingRevisionsHaveIndependentDurableState() {
        var first = identity();
        var second = identity();
        try (var scope = TenantContext.open(first)) {
            knowledge.save(document("same-id"));
            index.sync(false);
            var restarted = new KnowledgeIndexService(knowledge, states, vectors, ai, json, settings, "v1");
            assertThat(restarted.sync(false).skipped()).isEqualTo(1);
            var upgraded = new KnowledgeIndexService(knowledge, states, vectors, ai, json, settings, "v2");
            assertThat(upgraded.status().pending()).isEqualTo(1);
            assertThat(upgraded.sync(false).indexed()).isEqualTo(1);
        }
        try (var scope = TenantContext.open(second)) {
            knowledge.save(document("same-id"));
            assertThat(index.status().pending()).isEqualTo(1);
            assertThat(index.sync(false).indexed()).isEqualTo(1);
        }
    }

    @Test
    void duplicateImportReturnsExistingChunkButDifferentVersionIsPreserved() {
        try (var scope = TenantContext.open(identity())) {
            var first = service.add("title", "睡眠", "v1", "", "规律作息");
            var again = service.add("title", "睡眠", "v1", "", "规律作息");
            assertThat(again.id()).isEqualTo(first.id());
            service.add("title", "睡眠", "v2", "", "规律作息");
            assertThat(knowledge.list()).hasSize(2);
        }
    }
    @Test
    void activeTenantRejectsDuplicateSyncWithoutBlockingOtherTenantsOrNewKnowledge() {
        var first = identity();
        var second = identity();
        try (var scope = TenantContext.open(second)) {
            knowledge.save(document("b"));
        }
        doAnswer(invocation -> {
            assertThat(index.status().running()).isTrue();
            assertThatThrownBy(() -> index.sync(false)).isInstanceOf(HttpProblem.class);
            service.add("new", "睡眠", "v1", "", "同步期间新增");
            try (var scope = TenantContext.open(second)) {
                assertThat(index.sync(false).indexed()).isEqualTo(1);
            }
            return null;
        }).when(vector).index(eq(first.tenantId()), anyList());
        try (var scope = TenantContext.open(first)) {
            knowledge.save(document("a"));
            assertThat(index.sync(false).indexed()).isEqualTo(1);
            assertThat(index.status().running()).isFalse();
            assertThat(index.status().pending()).isEqualTo(1);
        }
    }

}
