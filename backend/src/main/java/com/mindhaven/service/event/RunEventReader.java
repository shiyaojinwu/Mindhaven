package com.mindhaven.service.event;

import com.mindhaven.integration.events.RunEventStore;
import com.mindhaven.common.EventCursor;

import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.model.chat.RunStreamEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Shared grouped XREAD, not consumer groups: all subscribers receive the same events. */
@Component
@ConditionalOnProperty(name = "mindhaven.events.store", havingValue = "redis")
public class RunEventReader {
    private final List<Set<Subscription>> groups = new ArrayList<>();
    private final ExecutorService workers;
    private final Semaphore capacity;
    private volatile boolean stopped;
    public RunEventReader(RunEventStore store, @Value("${mindhaven.events.reader-groups:2}") int count,
                          @Value("${mindhaven.events.max-subscribers:512}") int maximum) {
        if (count < 1 || count > 16 || maximum < 1) throw new IllegalArgumentException("Invalid SSE limits");
        capacity = new Semaphore(maximum);
        workers = Executors.newFixedThreadPool(count);
        for (int i = 0; i < count; i++) {
            Set<Subscription> group = ConcurrentHashMap.newKeySet(); groups.add(group);
            workers.submit(() -> readLoop(store, group));
        }
    }
    public Subscription subscribe(String id, String cursor, Predicate<RunStreamEvent> consumer) {
        if (!capacity.tryAcquire()) throw new HttpProblem(503, "实时连接已满，请稍后恢复连接");
        var group = groups.get(Math.floorMod(id.hashCode(), groups.size()));
        var subscription = new Subscription(id, cursor, consumer, group);
        group.add(subscription);
        return subscription;
    }
    public final class Subscription implements AutoCloseable {
        final String id;
        volatile String cursor;
        final Predicate<RunStreamEvent> consumer;
        final Set<Subscription> group;
        volatile boolean closed;
        long heartbeat = System.nanoTime();
        Subscription(String id, String cursor, Predicate<RunStreamEvent> consumer, Set<Subscription> group) {
            this.id = id; this.cursor = cursor; this.consumer = consumer; this.group = group;
        }
        void deliver(RunStreamEvent event) {
            if (closed || event.id() != null && EventCursor.compare(event.id(), cursor) <= 0) return;
            if (!consumer.test(event)) { close(); return; }
            if (event.id() != null) cursor = event.id();
            if (Set.of("terminal", "snapshot-required", "transport-error").contains(event.type())) close();
        }
        void heartbeat() {
            if (System.nanoTime() - heartbeat >= 15_000_000_000L) {
                deliver(new RunStreamEvent(null, "heartbeat", "{}")); heartbeat = System.nanoTime();
            }
        }
        @Override public synchronized void close() {
            if (!closed) { closed = true; group.remove(this); capacity.release(); }
        }
    }
    private void readLoop(RunEventStore store, Set<Subscription> group) {
        try (var reader = store.reader()) {
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                if (group.isEmpty()) { Thread.sleep(50); continue; }
                // Freeze recipients with their starting cursor before blocking. A new subscriber
                // joins the next pass, so a late join never skips its historical backlog.
                var subscribers = List.copyOf(group);
                Map<String, String> cursors = new HashMap<>();
                subscribers.forEach(s -> cursors.merge(s.id, s.cursor,
                        (a, b) -> EventCursor.compare(a, b) <= 0 ? a : b));
                try {
                    var pages = reader.readAfter(cursors, Duration.ofSeconds(5), 200);
                    for (var subscriber : subscribers) {
                        for (var event : pages.getOrDefault(subscriber.id, List.of())) subscriber.deliver(event);
                        subscriber.heartbeat();
                    }
                } catch (RuntimeException error) {
                    subscribers.forEach(s -> s.deliver(new RunStreamEvent(null, "transport-error", "{\"message\":\"实时连接暂不可用，正在恢复；后台任务继续执行\"}")));
                    Thread.sleep(500);
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    @PreDestroy public void stop() {
        stopped = true;
        groups.forEach(group -> List.copyOf(group).forEach(Subscription::close));
        workers.shutdownNow();
    }
}
