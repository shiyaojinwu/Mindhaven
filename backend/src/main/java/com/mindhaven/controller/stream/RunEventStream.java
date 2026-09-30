package com.mindhaven.controller.stream;

import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.model.chat.RunStreamEvent;
import com.mindhaven.service.ai.ChatRunService;
import com.mindhaven.common.EventCursor;
import com.mindhaven.service.event.RunEventReader;
import com.mindhaven.integration.events.RunEventStore;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Component
public class RunEventStream {
    private final ChatRunService runs;
    private final RunEventStore store;
    private final ObjectProvider<RunEventReader> readers;
    private final ObjectProvider<DatabaseRunEventStream> legacy;
    private final int maxEvents, maxBytes;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    // On-demand virtual sender tasks: at most one drain per admitted connection.
    // A socket blocked by a slow browser cannot occupy a shared XREAD/platform worker.
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    public RunEventStream(ChatRunService runs, RunEventStore store, ObjectProvider<RunEventReader> readers,
                          ObjectProvider<DatabaseRunEventStream> legacy,
                          @Value("${mindhaven.events.queue-events:256}") int maxEvents,
                          @Value("${mindhaven.events.queue-bytes:1048576}") int maxBytes) {
        this.runs = runs; this.store = store; this.readers = readers; this.legacy = legacy;
        if (maxEvents < 1 || maxBytes < 1) throw new IllegalArgumentException("Invalid SSE queue limits");
        this.maxEvents = maxEvents; this.maxBytes = maxBytes;
    }
    public SseEmitter open(String id, String after) {
        var run = runs.get(id); // Tenant AND owner authorization, before any Redis access.
        String cursor = EventCursor.validate(after);
        if (!store.ephemeral()) {
            if (cursor.contains("-")) throw new HttpProblem(409, "事件存储已切换，请重新打开会话");
            try { return legacy.getObject().open(id, Long.parseLong(cursor)); }
            catch (NumberFormatException e) { throw new HttpProblem(400, "事件游标无效"); }
        }
        var connection = new Connection();
        connections.add(connection);
        if (run.status().terminal()) {
            // DB is authoritative even if Redis/outbox is unavailable or retained events expired.
            connection.offer(new RunStreamEvent(null, "snapshot-required", "{}"));
        } else {
            try { connection.bind(readers.getObject().subscribe(id, cursor, connection::offer)); }
            catch (RuntimeException e) { connection.close(); throw e; }
        }
        return connection.emitter;
    }
    protected SseEmitter createEmitter() { return new SseEmitter(240000L); }

    private final class Connection {
        final SseEmitter emitter = createEmitter();
        final ArrayDeque<RunStreamEvent> queue = new ArrayDeque<>();
        int bytes;
        boolean draining, closed;
        RunEventReader.Subscription subscription;
        Connection() {
            emitter.onCompletion(this::close); emitter.onTimeout(this::close); emitter.onError(e -> close());
        }
        synchronized void bind(RunEventReader.Subscription value) { subscription = value; if (closed) value.close(); }
        synchronized boolean offer(RunStreamEvent event) {
            if (closed) return false;
            int size = event.payload().getBytes(StandardCharsets.UTF_8).length;
            if (queue.size() >= maxEvents || bytes + size > maxBytes) { close(); return false; }
            queue.add(event); bytes += size;
            if (!draining) {
                draining = true;
                try { senders.execute(this::drain); }
                catch (RejectedExecutionException e) { close(); return false; }
            }
            return true;
        }
        void drain() {
            // Yield after a bounded batch so busy clients cannot monopolize sender workers.
            for (int i = 0; i < 32; i++) {
                RunStreamEvent event;
                synchronized (this) {
                    if (closed) return;
                    event = queue.poll();
                    if (event == null) { draining = false; return; }
                    bytes -= event.payload().getBytes(StandardCharsets.UTF_8).length;
                }
                try {
                    if (event.type().equals("heartbeat")) emitter.send(SseEmitter.event().comment("keepalive"));
                    else {
                        var builder = SseEmitter.event().name(event.type()).data(event.payload(), MediaType.APPLICATION_JSON);
                        if (event.id() != null) builder.id(event.id());
                        emitter.send(builder);
                    }
                    if (Set.of("terminal", "snapshot-required", "transport-error").contains(event.type())) { close(); return; }
                } catch (Exception e) { close(); return; }
            }
            synchronized (this) {
                if (closed) return;
                try { senders.execute(this::drain); } catch (RejectedExecutionException e) { close(); }
            }
        }
        synchronized void close() {
            if (closed) return;
            closed = true; queue.clear(); bytes = 0;
            if (subscription != null) subscription.close();
            connections.remove(this);
            Thread.startVirtualThread(emitter::complete);
        }
    }
    @PreDestroy public void stop() { connections.forEach(Connection::close); senders.shutdownNow(); }
}
