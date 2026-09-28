package com.mindhaven.manager;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mindhaven.mapper.SessionMapper;
import com.mindhaven.model.chat.Session;
import com.mindhaven.model.entity.SessionEntity;
import com.mindhaven.security.TenantContext;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SessionManager {
    private final SessionMapper mapper;

    public SessionManager(SessionMapper mapper) {
        this.mapper = mapper;
    }

    private QueryWrapper<SessionEntity> scoped() {
        var identity = TenantContext.require();
        return new QueryWrapper<SessionEntity>().eq("tenant_id", identity.tenantId()).eq("owner_id", identity.userId());
    }

    public List<Session> list() {
        return mapper.selectList(scoped().orderByAsc("created_at", "id")).stream().map(this::model).toList();
    }

    public Optional<Session> get(String id) {
        return Optional.ofNullable(mapper.selectOne(scoped().eq("id", id))).map(this::model);
    }

    public void save(Session value) {
        var identity = TenantContext.require();
        var row = new SessionEntity();
        row.setRowId(UUID.randomUUID().toString());
        row.setTenantId(identity.tenantId());
        row.setOwnerId(identity.userId());
        row.setId(value.id());
        row.setTitle(value.title());
        row.setCreatedAt(value.createdAt());
        mapper.upsert(row);
    }

    private Session model(SessionEntity row) {
        return new Session(row.getId(), row.getTitle(), row.getCreatedAt());
    }
}
