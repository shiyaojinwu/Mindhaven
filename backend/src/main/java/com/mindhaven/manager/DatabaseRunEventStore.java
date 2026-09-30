package com.mindhaven.manager;

import com.mindhaven.mapper.RunEventMapper;
import com.mindhaven.mapper.RunMapper;
import com.mindhaven.model.chat.RunStreamEvent;
import com.mindhaven.security.TenantContext;
import com.mindhaven.integration.events.RunEventStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Migration adapter. Redis mode never uses this polling implementation. */
@Component
@ConditionalOnProperty(name = "mindhaven.events.store", havingValue = "database", matchIfMissing = true)
public class DatabaseRunEventStore implements RunEventStore {
    private final RunMapper runs;
    private final RunEventMapper events;
    public DatabaseRunEventStore(RunMapper runs, RunEventMapper events) { this.runs = runs; this.events = events; }
    @Override
    @Transactional
    public String append(String id, String type, String payload) {
        var who = TenantContext.require();
        var seq = runs.appendSequence(id, who.tenantId(), who.userId(), Instant.now().toString());
        if (seq.isEmpty()) throw new CancellationException("任务已停止");
        events.insert(id, seq.getFirst(), type, payload);
        return Long.toString(seq.getFirst());
    }
    @Override
    public void complete(String notificationId, String runId, String type, String payload, String terminal) {
        // Legacy terminal events remain in the same database transaction as the result.
    }
    @Override
    public Reader reader() {
        return new Reader() {
            public Map<String, List<RunStreamEvent>> readAfter(Map<String, String> cursors, Duration wait, int limit) {
                Map<String, List<RunStreamEvent>> result = new HashMap<>();
                cursors.forEach((id, cursor) -> result.put(id, events.events(id, Long.parseLong(cursor)).stream()
                        .limit(limit).map(e -> new RunStreamEvent(Long.toString(e.seq()), e.name(), e.payload())).toList()));
                if (result.values().stream().allMatch(List::isEmpty)) {
                    try { Thread.sleep(Math.min(wait.toMillis(), 100)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return result;
            }
            public void close() { }
        };
    }
}
