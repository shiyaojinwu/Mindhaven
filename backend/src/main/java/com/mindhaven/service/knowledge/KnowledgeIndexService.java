package com.mindhaven.service.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.common.Hashes;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.config.Settings;
import com.mindhaven.integration.vector.KnowledgeVectorIndex;
import com.mindhaven.manager.KnowledgeIndexManager;
import com.mindhaven.manager.KnowledgeManager;
import com.mindhaven.model.knowledge.IndexState;
import com.mindhaven.model.knowledge.Knowledge;
import com.mindhaven.model.vo.KnowledgeIndexResult;
import com.mindhaven.model.vo.KnowledgeIndexStatus;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.AiOperations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KnowledgeIndexService {
    private final KnowledgeManager knowledge;
    private final KnowledgeIndexManager states;
    private final ObjectProvider<KnowledgeVectorIndex> vectors;
    private final AiOperations ai;
    private final ObjectMapper json;
    private final String target;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public KnowledgeIndexService(KnowledgeManager knowledge, KnowledgeIndexManager states,
                                 ObjectProvider<KnowledgeVectorIndex> vectors, AiOperations ai,
                                 ObjectMapper json, Settings settings,
                                 @Value("${mindhaven.embedding-revision:v1}") String revision) {
        this.knowledge = knowledge;
        this.states = states;
        this.vectors = vectors;
        this.ai = ai;
        this.json = json;
        this.target = hash(List.of(settings.embeddingBaseUrl(), settings.embeddingPath(), settings.embeddingModel(),
                revision, settings.qdrantHost(), settings.qdrantPort(), settings.qdrantCollection()));
    }

    public KnowledgeIndexStatus status() {
        var documents = knowledge.list();
        Map<String, IndexState> stored = states.list(target);
        int indexed = 0, failed = 0;
        for (var document : documents) {
            var state = stored.get(document.id());
            if (matches(state, document)) {
                if (state.status().equals("INDEXED")) indexed++;
                else if (state.status().equals("FAILED")) failed++;
            }
        }
        return new KnowledgeIndexStatus(vectors.getIfAvailable() != null,
                active.contains(TenantContext.require().tenantId()), documents.size(), indexed,
                documents.size() - indexed - failed, failed);
    }

    public KnowledgeIndexResult sync(boolean force) {
        var vector = vectors.getIfAvailable();
        if (vector == null) throw new IllegalArgumentException("当前为本地 BM25 检索，无需向量索引");
        String tenant = TenantContext.require().tenantId();
        if (!active.add(tenant)) throw new HttpProblem(409, "本机构正在同步索引，请稍后刷新状态");
        try {
            var stored = states.list(target);
            int indexed = 0, skipped = 0;
            for (var document : knowledge.list()) {
                var state = stored.get(document.id());
                if (!force && matches(state, document) && state.status().equals("INDEXED")) {
                    skipped++;
                    continue;
                }
                String fingerprint = hash(document);
                // Commit pending before the remote write. Interrupted writes are safely retried by stable point ID.
                states.save(target, document.id(), fingerprint, "PENDING");
                try {
                    ai.embedding("knowledge-embedding", document.text(), () -> {
                        vector.index(tenant, List.of(document));
                        return 1;
                    });
                } catch (RuntimeException e) {
                    states.save(target, document.id(), fingerprint, "FAILED");
                    // Stop on provider failure; retain completed work and avoid retrying the entire corpus.
                    return new KnowledgeIndexResult(indexed, skipped, 1);
                }
                states.save(target, document.id(), fingerprint, "INDEXED");
                indexed++;
            }
            return new KnowledgeIndexResult(indexed, skipped, 0);
        } finally {
            active.remove(tenant);
        }
    }

    private boolean matches(IndexState state, Knowledge document) {
        return state != null && state.fingerprint().equals(hash(document));
    }

    private String hash(Object value) {
        try {
            return Hashes.sha256(json.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法计算索引指纹", e);
        }
    }
}
