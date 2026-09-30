package com.mindhaven.integration.events;

import com.mindhaven.model.chat.RunStreamEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Transport storage only. Ownership is checked by the subscription boundary. */
public interface RunEventStore {
    String append(String runId, String type, String payload);
    Reader reader();
    default void initialize(String runId) { }
    default boolean ephemeral() { return false; }
    void complete(String notificationId, String runId, String type, String payload, String terminal);

    interface Reader extends AutoCloseable {
        Map<String, List<RunStreamEvent>> readAfter(Map<String, String> cursors, Duration wait, int limit);
        default List<RunStreamEvent> readAfter(String runId, String cursor, Duration wait, int limit) {
            return readAfter(Map.of(runId, cursor), wait, limit).getOrDefault(runId, List.of());
        }
        void close();
    }
}
