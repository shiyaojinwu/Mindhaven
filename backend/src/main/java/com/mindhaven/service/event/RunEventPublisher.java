package com.mindhaven.service.event;

import com.mindhaven.integration.events.RunEventStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.model.chat.ChatEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** Per-run serialized batches; display failure must not roll back a business answer. */
@Service
public class RunEventPublisher {
    private final RunEventStore store;
    private final ObjectMapper json;
    private final Set<Batch> active = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    public RunEventPublisher(RunEventStore store, ObjectMapper json) {
        this.store = store; this.json = json;
        timer.scheduleWithFixedDelay(() -> active.forEach(Batch::flushDue), 50, 50, TimeUnit.MILLISECONDS);
    }
    public Batch open(String id) {
        var batch = new Batch(id);
        if (store.ephemeral()) {
            try { store.initialize(id); } catch (RuntimeException e) { batch.unavailable(e); }
            active.add(batch);
        }
        return batch;
    }
    public final class Batch implements AutoCloseable {
        private final String id;
        private final StringBuilder text = new StringBuilder();
        private long since;
        private boolean closed, unavailable;
        private boolean firstText = true;
        private Batch(String id) { this.id = id; }
        private void unavailable(RuntimeException error) {
            unavailable = true;
            text.setLength(0);
            LoggerFactory.getLogger(RunEventPublisher.class).warn("Display stream unavailable for run {} ({}); final result will use the database/outbox", id, error.getClass().getSimpleName());
        }
        public synchronized void accept(ChatEvent event) {
            if (closed) return;
            switch (event) {
                case ChatEvent.Delta delta -> {
                    if (unavailable) return;
                    if (text.isEmpty()) since = System.nanoTime();
                    text.append(delta.text());
                    if (firstText || text.length() >= 256 || System.nanoTime() - since >= 50_000_000L) { firstText = false; flush(); }
                }
                case ChatEvent.AnswerReset ignored -> { text.setLength(0); firstText = true; publish("answer-reset", Map.of()); }
                case ChatEvent.Sources value -> { flush(); publish("sources", value.citations()); }
                case ChatEvent.Recommendations value -> { flush(); publish("recommendations", value.items()); }
                case ChatEvent.AgentStatus value -> { flush(); publish("agent-status", value); }
                case ChatEvent.Done ignored -> flush();
            }
        }
        private synchronized void flushDue() {
            if (!closed && !text.isEmpty() && System.nanoTime() - since >= 50_000_000L) flush();
        }
        public synchronized void flush() {
            if (text.isEmpty()) return;
            String value = text.toString(); text.setLength(0);
            publish("delta", new ChatEvent.Delta(value));
        }
        private void publish(String type, Object payload) {
            if (unavailable) return;
            try { store.append(id, type, json.writeValueAsString(payload)); }
            catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid event payload", e); }
            catch (RuntimeException e) { if (!store.ephemeral()) throw e; unavailable(e); }
        }
        @Override public synchronized void close() { flush(); closed = true; active.remove(this); }
    }
    @PreDestroy public void stop() { timer.shutdownNow(); }
}
