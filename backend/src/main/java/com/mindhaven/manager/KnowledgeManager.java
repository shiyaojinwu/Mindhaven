package com.mindhaven.manager;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mindhaven.mapper.KnowledgeMapper;
import com.mindhaven.model.entity.KnowledgeEntity;
import com.mindhaven.model.knowledge.Knowledge;
import com.mindhaven.security.TenantContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class KnowledgeManager {
    private final KnowledgeMapper mapper;

    public KnowledgeManager(KnowledgeMapper mapper) {
        this.mapper = mapper;
    }

    private QueryWrapper<KnowledgeEntity> scoped() {
        var identity = TenantContext.require();
        return new QueryWrapper<KnowledgeEntity>().eq("tenant_id", identity.tenantId());
    }

    public List<Knowledge> list() {
        return mapper.selectList(scoped().orderByAsc("created_at", "id")).stream().map(this::model).toList();
    }

    public Optional<Knowledge> get(String id) {
        return Optional.ofNullable(mapper.selectOne(scoped().eq("id", id))).map(this::model);
    }

    public void save(Knowledge value) {
        var identity = TenantContext.require();
        var row = new KnowledgeEntity();
        row.setRowId(UUID.randomUUID().toString());
        row.setTenantId(identity.tenantId());
        row.setId(value.id());
        row.setTitle(value.title());
        row.setTopic(value.topic());
        row.setVersion(value.version());
        row.setSourceUrl(value.sourceUrl());
        row.setText(value.text());
        row.setCreatedAt(Instant.now().toString());
        mapper.upsert(row);
    }

    private Knowledge model(KnowledgeEntity row) {
        return new Knowledge(row.getId(), row.getTitle(), row.getTopic(), row.getVersion(), row.getSourceUrl(), row.getText());
    }
}
