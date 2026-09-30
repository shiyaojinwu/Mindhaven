package com.mindhaven.service.event;

import com.mindhaven.integration.events.RunEventStore;

import com.mindhaven.manager.RunOutboxManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.*;

@Component
public class OutboxPublisher {
    private final RunOutboxManager outbox;
    private final RunEventStore store;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private int failures;
    public OutboxPublisher(RunOutboxManager outbox, RunEventStore store) { this.outbox = outbox; this.store = store; }
    @EventListener(ApplicationReadyEvent.class)
    public void start() { if (store.ephemeral()) timer.scheduleWithFixedDelay(this::tick, 0, 1000, TimeUnit.MILLISECONDS); }
    public synchronized void publishPending() {
        for (var item : outbox.pending()) {
            store.complete(item.id(), item.runId(), item.eventType(), item.payload(), item.terminalPayload());
            outbox.delivered(item.id());
        }
    }
    private void tick() {
        try { publishPending(); failures = 0; }
        catch (RuntimeException e) {
            if (failures++ % 30 == 0) LoggerFactory.getLogger(getClass()).warn("Run notification delivery unavailable ({})", e.getClass().getSimpleName());
        }
    }
    @PreDestroy public void stop() { timer.shutdownNow(); }
}
