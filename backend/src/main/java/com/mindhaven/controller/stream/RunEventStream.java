package com.mindhaven.controller.stream;

import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.ChatRunService;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.*;

/**
 * Transport disconnects only detach a subscriber; they never execute or cancel model work.
 */
@Component
public class RunEventStream {
    private final ChatRunService runs;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Set<SseEmitter> subscribers = ConcurrentHashMap.newKeySet();

    public RunEventStream(ChatRunService runs) {
        this.runs = runs;
    }

    public SseEmitter open(String id, long after) {
        runs.get(id);
        var identity = TenantContext.require();
        var emitter = new SseEmitter(240000L);
        subscribers.add(emitter);
        long[] cursor = {Math.max(0, after)};
        long[] heartbeat = {System.nanoTime()};
        ScheduledFuture<?>[] polling = {null};
        Runnable close = () -> {
            subscribers.remove(emitter);
            if (polling[0] != null) polling[0].cancel(false);
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(e -> close.run());
        polling[0] = scheduler.scheduleWithFixedDelay(() -> {
            try (var scope = TenantContext.open(identity)) {
                // Read terminal state before the event page: a concurrent completion must not
                // make us close a subscription before its final events have been read.
                var run = runs.get(id);
                var events = runs.events(id, cursor[0]);
                for (var event : events) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.name()).data(event.payload(), MediaType.APPLICATION_JSON));
                    cursor[0] = event.seq();
                }
                if (events.size() < 200 && run.status().terminal()) {
                    emitter.send(SseEmitter.event().name("terminal").data(run));
                    emitter.complete();
                    close.run();
                } else if (System.nanoTime() - heartbeat[0] > 15_000_000_000L) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    heartbeat[0] = System.nanoTime();
                }
            } catch (Exception e) {
                emitter.complete();
                close.run();
            }
        }, 20, 100, TimeUnit.MILLISECONDS);
        return emitter;
    }

    @PreDestroy
    void stop() {
        subscribers.forEach(SseEmitter::complete);
        scheduler.shutdownNow();
    }
}
