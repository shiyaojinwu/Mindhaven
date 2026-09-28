package com.mindhaven.manager;

import com.mindhaven.mapper.KnowledgeIndexMapper;
import com.mindhaven.model.knowledge.IndexState;
import com.mindhaven.security.TenantContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class KnowledgeIndexManager {
    private final KnowledgeIndexMapper mapper;

    public KnowledgeIndexManager(KnowledgeIndexMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, IndexState> list(String target) {
        return mapper.list(TenantContext.require().tenantId(), target).stream()
                .collect(Collectors.toMap(IndexState::chunkId, Function.identity()));
    }

    public void save(String target, String chunk, String fingerprint, String status) {
        mapper.save(TenantContext.require().tenantId(), target,
                new IndexState(chunk, fingerprint, status, Instant.now().toString()));
    }
}
