package com.mindhaven.controller.stream;

import com.mindhaven.integration.events.RunEventStore;
import com.mindhaven.model.chat.AiRun;
import com.mindhaven.model.chat.RunStreamEvent;
import com.mindhaven.service.ai.ChatRunService;
import com.mindhaven.service.event.RunEventReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunEventStreamTest {
    static class TestEmitter extends SseEmitter {
        final CountDownLatch entered = new CountDownLatch(1), unblock, completed = new CountDownLatch(1);
        TestEmitter(boolean slow) { unblock = new CountDownLatch(slow ? 1 : 0); }
        @Override public void send(SseEventBuilder builder) throws IOException {
            entered.countDown();
            try { if (!unblock.await(5, TimeUnit.SECONDS)) throw new IOException("slow socket"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }
        @Override public void complete() { completed.countDown(); }
    }
    @Test @SuppressWarnings("unchecked")
    void slowClientQueueIsBoundedAndDoesNotBlockOtherClients() throws Exception {
        var runs = mock(ChatRunService.class);
        var store = mock(RunEventStore.class);
        when(store.ephemeral()).thenReturn(true);
        var reader = mock(RunEventReader.class);
        ObjectProvider<RunEventReader> readers = mock(ObjectProvider.class);
        when(readers.getObject()).thenReturn(reader);
        ObjectProvider<DatabaseRunEventStream> legacy = mock(ObjectProvider.class);
        List<Predicate<RunStreamEvent>> consumers = new ArrayList<>();
        when(reader.subscribe(anyString(), anyString(), any())).thenAnswer(call -> {
            consumers.add(call.getArgument(2)); return mock(RunEventReader.Subscription.class);
        });
        when(runs.get(anyString())).thenAnswer(call -> new AiRun(call.getArgument(0), "s", "r", "hi", AiRun.Status.RUNNING, false, "now", "now", null));
        var slow = new TestEmitter(true);
        var fast = new TestEmitter(false);
        var emitters = new ArrayDeque<SseEmitter>(List.of(slow, fast));
        var stream = new RunEventStream(runs, store, readers, legacy, 2, 1024) {
            @Override protected SseEmitter createEmitter() { return emitters.remove(); }
        };
        try {
            stream.open("slow", "0"); stream.open("fast", "0");
            var event = new RunStreamEvent("10-0", "delta", "{\"text\":\"hi\"}");
            consumers.get(0).test(event);
            assertThat(slow.entered.await(1, TimeUnit.SECONDS)).isTrue();
            consumers.get(0).test(event); consumers.get(0).test(event);
            assertThat(consumers.get(0).test(event)).isFalse();
            consumers.get(1).test(event);
            assertThat(fast.entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(slow.completed.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { slow.unblock.countDown(); stream.stop(); }
    }
    @Test @SuppressWarnings("unchecked")
    void authorizationRunsBeforeEventStoreOrSubscription() {
        var runs = mock(ChatRunService.class);
        when(runs.get("private")).thenThrow(new NoSuchElementException());
        var store = mock(RunEventStore.class);
        ObjectProvider<RunEventReader> readers = mock(ObjectProvider.class);
        ObjectProvider<DatabaseRunEventStream> legacy = mock(ObjectProvider.class);
        var stream = new RunEventStream(runs, store, readers, legacy, 2, 1024);
        try { assertThatThrownBy(() -> stream.open("private", "0")).isInstanceOf(NoSuchElementException.class); }
        finally { stream.stop(); }
        verifyNoInteractions(store, readers, legacy);
    }
}
