package com.mindhaven.manager;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.integration.events.RunEventStore;
import org.springframework.beans.factory.annotation.Autowired;
import com.mindhaven.mapper.*;
import com.mindhaven.model.chat.AiRun;
import com.mindhaven.model.entity.RunEntity;
import com.mindhaven.security.TenantContext;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

@Component
@DependsOnDatabaseInitialization
public class RunManager {
    private final RunMapper runs;
    private final RunEventMapper events;
    private final ObjectMapper json;
    private RunEventStore eventStore;
    private RunOutboxMapper outbox;
    @Autowired
    public void eventStore(RunEventStore store, RunOutboxMapper outbox) {
        this.eventStore = store;
        this.outbox = outbox;
    }

    public RunManager(RunMapper runs, RunEventMapper events, ObjectMapper json) {
        this.runs = runs;
        this.events = events;
        this.json = json;
    }

    private QueryWrapper<RunEntity> owned() {
        var who = TenantContext.require();
        return new QueryWrapper<RunEntity>().eq("tenant_id", who.tenantId()).eq("owner_id", who.userId());
    }

    private UpdateWrapper<RunEntity> ownedUpdate(String id) {
        var who = TenantContext.require();
        return new UpdateWrapper<RunEntity>().eq("tenant_id", who.tenantId()).eq("owner_id", who.userId()).eq("id", id);
    }

    private AiRun model(RunEntity r) {
        return new AiRun(r.getId(), r.getSessionId(), r.getRequestId(), r.getMessage(), AiRun.Status.valueOf(r.getStatus()), r.getCancelRequested() != 0, r.getCreatedAt(), r.getUpdatedAt(), r.getError());
    }

    private RunEntity findRequest(String session, String request) {
        return runs.selectOne(owned().eq("session_id", session).eq("request_id", request));
    }

    private AiRun.Created existing(RunEntity run, String hash) {
        if (!hash.equals(run.getRequestHash())) throw new HttpProblem(409, "请求标识已用于另一条消息，请创建新请求");
        return new AiRun.Created(model(run), false);
    }

    public AiRun.Created create(String sessionId, String requestId, String hash, String message) {
        var who = TenantContext.require();
        var existing = findRequest(sessionId, requestId);
        if (existing != null) return existing(existing, hash);
        var row = new RunEntity();
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        row.setId(id);
        row.setTenantId(who.tenantId());
        row.setOwnerId(who.userId());
        row.setSessionId(sessionId);
        row.setRequestId(requestId);
        row.setRequestHash(hash);
        row.setMessage(message);
        row.setStatus("QUEUED");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            runs.insert(row);
            return new AiRun.Created(get(id), true);
        } catch (DataAccessException e) {
            if (!uniqueViolation(e)) throw e;
            existing = findRequest(sessionId, requestId);
            if (existing != null) return existing(existing, hash);
            throw new HttpProblem(409, "已有任务正在进行，请等待或停止后重试");
        }
    }

    private boolean uniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && ("23505".equals(sql.getSQLState()) || (sql.getErrorCode() == 19 && sql.getMessage().contains("SQLITE_CONSTRAINT_UNIQUE"))))
                return true;
        }
        return error instanceof DuplicateKeyException;
    }

    public AiRun get(String id) {
        var row = runs.selectOne(owned().eq("id", id));
        if (row == null) throw new NoSuchElementException("任务不存在");
        return model(row);
    }

    public List<AiRun> recent(String session) {
        return runs.selectList(owned().eq("session_id", session).orderByDesc("created_at", "id").last("LIMIT 20")).stream().map(this::model).toList();
    }

    public boolean start(String id) {
        get(id);
        return runs.update(null, ownedUpdate(id).eq("status", "QUEUED").eq("cancel_requested", 0).set("status", "RUNNING").set("updated_at", Instant.now().toString())) == 1;
    }

    public void requestCancel(String id) {
        get(id);
        runs.update(null, ownedUpdate(id).in("status", "QUEUED", "RUNNING").set("cancel_requested", 1).set("updated_at", Instant.now().toString()));
    }

    @Transactional
    public void append(String id, String name, Object payload) {
        get(id);
        var who = TenantContext.require();
        var seq = runs.appendSequence(id, who.tenantId(), who.userId(), Instant.now().toString());
        if (seq.isEmpty()) throw new CancellationException("任务已停止");
        insert(id, seq.getFirst(), name, payload);
    }

    @Transactional
    public void finish(String id, AiRun.Status status, String name, Object payload, String error) {
        get(id);
        if (!status.terminal()) throw new IllegalArgumentException("Expected terminal state");
        var who = TenantContext.require();
        var seq = runs.finishSequence(id, who.tenantId(), who.userId(), status.name(), Instant.now().toString(), error);
        if (!seq.isEmpty()) {
            if (eventStore.ephemeral()) {
                try {
                    outbox.insert(id + ":terminal", id, name, json.writeValueAsString(payload),
                            json.writeValueAsString(get(id)), Instant.now().toString());
                } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
            } else insert(id, seq.getFirst(), name, payload);
        }
        else if (status == AiRun.Status.COMPLETED && get(id).cancelRequested())
            throw new CancellationException("任务已停止");
    }

    private void insert(String id, long seq, String name, Object payload) {
        try {
            events.insert(id, seq, name, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<AiRun.Event> events(String id, long after) {
        get(id);
        return events.events(id, Math.max(0, after));
    }

    @Transactional
    public void recoverInterrupted() {
        // Startup recovery deliberately spans tenants in this single-runtime deployment.
        var interrupted = runs.selectList(new QueryWrapper<RunEntity>().in("status", "QUEUED", "RUNNING"));
        runs.update(null, new UpdateWrapper<RunEntity>().in("status", "QUEUED", "RUNNING").set("status", "INTERRUPTED").set("error", "服务已重启，本次任务未自动重试").set("updated_at", Instant.now().toString()));
        if (eventStore.ephemeral()) for (var row : interrupted) {
            row.setStatus("INTERRUPTED");
            row.setError("服务已重启，本次任务未自动重试");
            row.setUpdatedAt(Instant.now().toString());
            try {
                outbox.insert(row.getId() + ":terminal", row.getId(), "error",
                        json.writeValueAsString(Map.of("message", row.getError())),
                        json.writeValueAsString(model(row)), row.getUpdatedAt());
            } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        }
    }
}
