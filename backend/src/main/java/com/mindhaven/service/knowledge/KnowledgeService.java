package com.mindhaven.service.knowledge;

import com.mindhaven.config.RetrievalSettings;
import com.mindhaven.integration.vector.KnowledgeVectorIndex;
import com.mindhaven.manager.KnowledgeManager;
import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.model.knowledge.Knowledge;
import com.mindhaven.model.knowledge.RetrievalResult;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.AiOperations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class KnowledgeService {
    private final KnowledgeManager store;
    private final ObjectProvider<KnowledgeVectorIndex> vectors;
    private final AiOperations ai;
    private final LexicalRetriever lexical;
    private final ReciprocalRankFusion fusion;
    private final RetrievalSettings settings;

    public KnowledgeService(KnowledgeManager store, ObjectProvider<KnowledgeVectorIndex> vectors, AiOperations ai, LexicalRetriever lexical, ReciprocalRankFusion fusion, RetrievalSettings settings) {
        this.store = store;
        this.vectors = vectors;
        this.ai = ai;
        this.lexical = lexical;
        this.fusion = fusion;
        this.settings = settings;
    }

    public List<Knowledge> all() {
        return store.list();
    }

    public Knowledge get(String id) {
        return store.get(id).orElseThrow(() -> new NoSuchElementException("知识片段不存在"));
    }

    public synchronized Knowledge add(String title, String topic, String version, String url, String text) {
        var existing = all().stream().filter(k -> k.title().equals(title) && k.topic().equals(topic)
                && k.version().equals(version) && k.sourceUrl().equals(url) && k.text().equals(text)).findFirst();
        if (existing.isPresent()) return existing.get();
        // Immutable chunks: updates become a new ID and can be pinned by version.
        Knowledge k = new Knowledge(UUID.randomUUID().toString(), title, topic, version, url, text);
        store.save(k);
        return k;
    }

    public List<Citation> search(String query, String topic, String version, int k) {
        return retrieve(query, topic, version, k).citations();
    }

    public RetrievalResult retrieve(String query, String topic, String version, int k) {
        if (k < 1 || k > settings.candidateLimit()) throw new IllegalArgumentException("检索数量超出候选范围");
        String tenant = TenantContext.require().tenantId();
        // The canonical store is tenant-scoped. Both channels use the same topic/version boundary.
        var eligible = all().stream().filter(x -> version.equals(x.version()) && (topic.equals("全部") || topic.equals(x.topic()))).toList();
        var vector = vectors.getIfAvailable();
        String mode = vector == null ? "bm25" : settings.mode().equals("hybrid") ? "hybrid-rrf" : "dense";
        int candidates = mode.equals("hybrid-rrf") ? settings.candidateLimit() : k;
        var config = new RetrievalResult.Configuration(candidates, settings.rrfK(), settings.vectorThreshold());
        if (eligible.isEmpty()) return new RetrievalResult(mode, List.of(), List.of(), config);
        var keywords = mode.equals("dense") ? List.<Citation>of() : lexical.search(query, eligible, candidates);
        if (vector == null) return singleChannel("bm25", keywords, config);
        Map<String, Knowledge> canonical = new HashMap<>();
        eligible.forEach(d -> canonical.put(d.id(), d));
        var hits = ai.embedding("query-embedding", query, () -> vector.search(tenant, query, topic, version, candidates, settings.vectorThreshold()));
        Map<String, Citation> unique = new LinkedHashMap<>();
        for (var hit : hits) {
            var doc = canonical.get(hit.chunkId());
            // Drop stale or out-of-scope hits; never copy untrusted vector payload text into context.
            if (doc != null && Double.isFinite(hit.score())) unique.putIfAbsent(doc.id(), citation(doc, hit.score()));
        }
        var dense = unique.values().stream().limit(candidates).toList();
        if (mode.equals("dense")) return singleChannel(mode, dense, config);
        var result = fusion.fuse(dense, keywords, settings.rrfK(), k);
        return new RetrievalResult(mode, result.citations(), result.matches(), config);
    }

    private RetrievalResult singleChannel(String mode, List<Citation> docs, RetrievalResult.Configuration config) {
        List<RetrievalResult.Match> matches = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            var c = docs.get(i);
            boolean dense = mode.equals("dense");
            matches.add(new RetrievalResult.Match(c.id(), dense ? i + 1 : null, dense ? null : i + 1, dense ? c.score() : null, dense ? null : c.score(), null));
        }
        return new RetrievalResult(mode, docs, matches, config);
    }

    static Citation citation(Knowledge k, double score) {
        return new Citation(k.id(), k.title(), k.topic(), k.version(), k.sourceUrl(), k.text(), score);
    }
}
