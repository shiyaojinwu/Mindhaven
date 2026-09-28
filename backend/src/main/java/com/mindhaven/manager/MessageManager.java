package com.mindhaven.manager;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.mapper.MessageMapper;
import com.mindhaven.model.chat.CitationCheck;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.entity.MessageEntity;
import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.security.TenantContext;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MessageManager {
    private final MessageMapper mapper;
    private final ObjectMapper json;

    public MessageManager(MessageMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    private QueryWrapper<MessageEntity> scoped() {
        var identity = TenantContext.require();
        return new QueryWrapper<MessageEntity>().eq("tenant_id", identity.tenantId()).eq("owner_id", identity.userId());
    }

    public List<ChatMessage> list(String sessionId) {
        return mapper.selectList(scoped().eq("session_id", sessionId).orderByAsc("seq")).stream().map(this::model).toList();
    }

    public List<ChatMessage> completeAfter(String sessionId, long coveredThroughSeq) {
        return mapper.selectList(scoped().eq("session_id", sessionId).eq("status", "complete").gt("seq", coveredThroughSeq).orderByAsc("seq")).stream().map(this::model).toList();
    }

    public long maxSequence(String sessionId) {
        var rows = mapper.selectList(scoped().eq("session_id", sessionId).orderByDesc("seq").last("LIMIT 1"));
        return rows.isEmpty() ? 0 : rows.getFirst().getSeq();
    }

    public Optional<ChatMessage> get(String id) {
        return Optional.ofNullable(mapper.selectOne(scoped().eq("id", id))).map(this::model);
    }

    public void save(ChatMessage value) {
        var identity = TenantContext.require();
        var row = new MessageEntity();
        row.setRowId(UUID.randomUUID().toString());
        row.setTenantId(identity.tenantId());
        row.setOwnerId(identity.userId());
        row.setId(value.id());
        row.setSessionId(value.sessionId());
        row.setSeq(value.seq());
        row.setRole(value.role());
        row.setContent(value.content());
        row.setCreatedAt(value.createdAt());
        row.setStatus(value.status());
        row.setCitationsJson(write(value.citations()));
        row.setCitationCheckJson(value.citationCheck() == null ? null : write(value.citationCheck()));
        mapper.upsert(row);
    }

    private ChatMessage model(MessageEntity row) {
        return new ChatMessage(row.getId(), row.getSessionId(), row.getSeq(), row.getRole(), row.getContent(), row.getCreatedAt(), readCitations(row.getCitationsJson()), row.getStatus(), readCheck(row.getCitationCheckJson()));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode message snapshot", e);
        }
    }

    private List<Citation> readCitations(String value) {
        try {
            return json.readValue(value, new TypeReference<List<Citation>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot decode citations", e);
        }
    }

    private CitationCheck readCheck(String value) {
        if (value == null) return null;
        try {
            return json.readValue(value, CitationCheck.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot decode citation check", e);
        }
    }
}
