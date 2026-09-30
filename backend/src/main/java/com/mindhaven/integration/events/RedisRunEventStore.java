package com.mindhaven.integration.events;

import com.mindhaven.model.chat.RunStreamEvent;
import com.mindhaven.common.EventCursor;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

/** One ordinary connection and a bounded number of dedicated blocking reader connections. */
@Component
@ConditionalOnProperty(name = "mindhaven.events.store", havingValue = "redis")
public class RedisRunEventStore implements RunEventStore {
    private final RedisClient client;
    private final int retention;
    private volatile StatefulRedisConnection<String, String> writer;
    public RedisRunEventStore(@Value("${mindhaven.events.redis-uri:redis://127.0.0.1:6379}") String uri,
                              @Value("${mindhaven.events.retention-seconds:1800}") int retention) {
        this.retention = retention;
        client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder().autoReconnect(true).requestQueueSize(256)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build());
        client.setDefaultTimeout(Duration.ofSeconds(2));
    }
    private synchronized StatefulRedisConnection<String, String> writer() {
        if (writer == null) writer = client.connect();
        return writer;
    }
    private String key(String id) { return "mindhaven:run:" + id + ":events"; }
    @Override public boolean ephemeral() { return true; }
    @Override public void initialize(String id) {
        writer().sync().eval("if redis.call('EXISTS',KEYS[1]) == 0 then return redis.call('XADD',KEYS[1],'*','type','stream-start','payload','{}','schemaVersion','1') end return ''",
                ScriptOutputType.VALUE, new String[]{key(id)});
    }
    @Override public String append(String id, String type, String payload) {
        // Never silently recreate a lost active stream: that would make a partial replay look complete.
        String result = writer().sync().eval("if redis.call('EXISTS',KEYS[1]) == 0 or redis.call('EXISTS',KEYS[2]) == 1 then return false end return redis.call('XADD',KEYS[1],'*','type',ARGV[1],'payload',ARGV[2],'schemaVersion','1')",
                ScriptOutputType.VALUE, new String[]{key(id), key(id) + ":closed"}, type, payload);
        if (result == null) throw new IllegalStateException("Event stream unavailable");
        return result;
    }
    @Override public void complete(String notificationId, String id, String type, String payload, String terminal) {
        // Atomic terminal publication + deduplication. A recreated stream is explicitly incomplete.
        writer().sync().eval("""
                if redis.call('EXISTS',KEYS[2]) == 1 then return 0 end
                if redis.call('EXISTS',KEYS[1]) == 0 then
                  redis.call('XADD',KEYS[1],'*','type','snapshot-required','payload','{}','schemaVersion','1')
                end
                redis.call('XADD',KEYS[1],'*','type',ARGV[1],'payload',ARGV[2],'schemaVersion','1')
                redis.call('XADD',KEYS[1],'*','type','terminal','payload',ARGV[3],'schemaVersion','1')
                redis.call('EXPIRE',KEYS[1],ARGV[4])
                redis.call('SET',KEYS[2],'1','EX',ARGV[4])
                redis.call('SET',KEYS[3],'1','EX',ARGV[4])
                return 1
                """, ScriptOutputType.INTEGER, new String[]{key(id), key(id) + ":notification:" + notificationId, key(id) + ":closed"},
                type, payload, terminal, Integer.toString(retention));
    }
    @Override public Reader reader() {
        return new Reader() {
            private StatefulRedisConnection<String, String> connection;
            private StatefulRedisConnection<String, String> connection() {
                if (connection == null) {
                    connection = client.connect();
                    connection.setTimeout(Duration.ofSeconds(7));
                }
                return connection;
            }
            public Map<String, List<RunStreamEvent>> readAfter(Map<String, String> cursors, Duration wait, int limit) {
                Map<String, List<RunStreamEvent>> result = new HashMap<>();
                List<XReadArgs.StreamOffset<String>> offsets = new ArrayList<>();
                Map<String, String> keys = new HashMap<>();
                var commands = connection().sync();
                for (var entry : cursors.entrySet()) {
                    String id = entry.getKey(), cursor = entry.getValue(), stream = key(id);
                    var first = commands.xrange(stream, Range.unbounded(), Limit.from(1));
                    boolean gap = first.isEmpty() || (!cursor.equals("0") && !cursor.equals("0-0") &&
                            EventCursor.compare(cursor, first.getFirst().getId()) < 0) ||
                            ((cursor.equals("0") || cursor.equals("0-0")) && !first.getFirst().getBody().get("type").equals("stream-start"));
                    if (!gap && !cursor.equals("0") && !cursor.equals("0-0")) {
                        // Exact ID must still exist; catches trimmed/deleted cursors and future cursors.
                        gap = commands.xrange(stream, Range.create(cursor, cursor), Limit.from(1)).isEmpty();
                    }
                    if (gap) result.put(id, List.of(new RunStreamEvent(null, "snapshot-required", "{}")));
                    else { offsets.add(XReadArgs.StreamOffset.from(stream, cursor)); keys.put(stream, id); }
                }
                if (!offsets.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    XReadArgs.StreamOffset<String>[] array = offsets.toArray(new XReadArgs.StreamOffset[0]);
                    var args = new XReadArgs().count(limit);
                    if (result.isEmpty() && !wait.isZero()) args.block(wait);
                    var messages = commands.xread(args, array);
                    if (messages != null) for (StreamMessage<String, String> message : messages) {
                        result.computeIfAbsent(keys.get(message.getStream()), ignored -> new ArrayList<>())
                                .add(new RunStreamEvent(message.getId(), message.getBody().get("type"), message.getBody().get("payload")));
                    }
                }
                return result;
            }
            public void close() { if (connection != null) connection.close(); }
        };
    }
    @PreDestroy public void close() { if (writer != null) writer.close(); client.shutdown(); }
}
