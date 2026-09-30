package com.mindhaven;

import com.mindhaven.integration.events.RunEventStore;
import com.mindhaven.manager.RunManager;
import com.mindhaven.manager.RunOutboxManager;
import com.mindhaven.model.chat.AiRun;
import com.mindhaven.model.chat.RunStreamEvent;
import com.mindhaven.model.dto.ChatCommand;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.ai.ChatRunService;
import com.mindhaven.service.auth.AuthService;
import com.mindhaven.service.chat.SessionService;
import com.mindhaven.service.event.OutboxPublisher;
import com.mindhaven.service.event.RunEventReader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.Range;
import java.net.*;
import java.net.http.*;
import java.io.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.datasource.url=jdbc:sqlite::memory:", "mindhaven.ai-mode=demo",
        "mindhaven.vector-mode=local", "mindhaven.events.store=redis", "mindhaven.events.retention-seconds=30"})
@EnabledIfEnvironmentVariable(named = "REDIS_SERVER", matches = ".+")
class RedisEventsTest {
    static Process redis;
    static int port;
    static Path directory;
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) throws Exception {
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        directory = Files.createTempDirectory("mindhaven-redis-test-");
        startRedis();
        registry.add("mindhaven.events.redis-uri", () -> "redis://127.0.0.1:" + port);
    }
    static void startRedis() throws Exception {
        redis = new ProcessBuilder(System.getenv("REDIS_SERVER"), "--port", Integer.toString(port), "--bind", "127.0.0.1",
                "--save", "", "--appendonly", "no", "--dir", directory.toString())
                .redirectOutput(directory.resolve("redis.log").toFile()).redirectErrorStream(true).start();
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            try (var socket = new Socket("127.0.0.1", port)) { return true; }
            catch (Exception ignored) { return false; }
        });
    }
    static void stopRedis() throws Exception { redis.destroy(); redis.waitFor(); }
    @AfterAll static void cleanup() throws Exception { if (redis != null && redis.isAlive()) stopRedis(); }
    @LocalServerPort int httpPort;
    @Autowired RunEventStore store;
    @Autowired RunEventReader reader;
    @Autowired RunManager runs;
    @Autowired RunOutboxManager outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired ChatRunService runtime;
    @Autowired AuthService auth;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void realHttpSseUsesStringIdsAndLastEventHeaderForReplay() throws Exception {
        var account = auth.register("http-" + UUID.randomUUID(), "HTTP", "admin", "test-password-123");
        String id;
        try (var scope = TenantContext.open(account.identity())) {
            id = runs.create(sessions.create().id(), UUID.randomUUID().toString(), "hash", "hi").run().id();
        }
        store.initialize(id);
        String first = store.append(id, "delta", "{\"text\":\"hello\"}");
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/api/runs/" + id + "/events"))
                    .header("Cookie", "mindhaven_session=" + account.token()).timeout(Duration.ofSeconds(15)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var lines = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                List<String> block = new ArrayList<>();
                boolean found = false;
                while ((line = lines.readLine()) != null) {
                    if (!line.isEmpty()) { block.add(line); continue; }
                    if (block.contains("id:" + first)) {
                        assertThat(block).contains("event:delta", "data:{\"text\":\"hello\"}");
                        found = true; break;
                    }
                    block.clear();
                }
                assertThat(found).isTrue();
            }
            String reset = store.append(id, "answer-reset", "{}");
            var next = HttpRequest.newBuilder(request.uri()).header("Cookie", "mindhaven_session=" + account.token())
                    .header("Last-Event-ID", first).timeout(Duration.ofSeconds(15)).build();
            var replay = client.send(next, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(replay.statusCode()).isEqualTo(200);
            try (var lines = new BufferedReader(new InputStreamReader(replay.body()))) {
                assertThat(lines.readLine()).isEqualTo("event:answer-reset");
                assertThat(lines.readLine()).isEqualTo("data:{}");
                assertThat(lines.readLine()).isEqualTo("id:" + reset);
            }
        }
    }

    @Test void replayBroadcastAndLateJoinKeepEveryEventIncludingReset() throws Exception {
        String id = UUID.randomUUID().toString(); store.initialize(id);
        String first = store.append(id, "delta", "{\"text\":\"old\"}");
        List<RunStreamEvent> a = new CopyOnWriteArrayList<>(), b = new CopyOnWriteArrayList<>();
        try (var sa = reader.subscribe(id, "0", e -> a.add(e))) {
            await().atMost(Duration.ofSeconds(8)).until(() -> a.stream().anyMatch(e -> first.equals(e.id())));
            try (var sb = reader.subscribe(id, "0", e -> b.add(e))) {
                String reset = store.append(id, "answer-reset", "{}");
                String next = store.append(id, "delta", "{\"text\":\"new\"}");
                await().atMost(Duration.ofSeconds(12)).until(() -> b.stream().anyMatch(e -> next.equals(e.id())));
                await().atMost(Duration.ofSeconds(8)).until(() -> a.stream().anyMatch(e -> next.equals(e.id())));
                assertThat(a).extracting(RunStreamEvent::id).containsSubsequence(first, reset, next);
                assertThat(b).extracting(RunStreamEvent::id).containsSubsequence(first, reset, next);
                try (var replay = store.reader()) {
                    assertThat(replay.readAfter(id, first, Duration.ZERO, 200)).extracting(RunStreamEvent::type)
                            .containsExactly("answer-reset", "delta");
                }
            }
        }
    }
    @Test void redisLossDoesNotLoseFinalAnswerAndOutboxRetriesIdempotently() throws Exception {
        var account = auth.register("redis-" + UUID.randomUUID(), "Redis", "admin", "test-password-123");
        String id;
        try (var scope = TenantContext.open(account.identity())) {
            var session = sessions.create();
            stopRedis();
            try {
                id = runtime.create(session.id(), new ChatCommand("你好", "全部", "v1", false, UUID.randomUUID().toString())).id();
                await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(() -> runs.get(id).status().terminal());
                assertThat(runs.get(id).status()).isEqualTo(AiRun.Status.COMPLETED);
                assertThat(sessions.history(session.id())).hasSize(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_run_events WHERE run_id=?", Integer.class, id)).isZero();
                assertThat(outbox.pending()).anyMatch(n -> n.runId().equals(id));
            } finally { startRedis(); }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                publisher.publishPending();
                assertThat(outbox.pending()).noneMatch(n -> n.runId().equals(id));
            });
            // Missing stream is a recovery signal, not a silently truncated answer.
            try (var replay = store.reader()) {
                assertThat(replay.readAfter(id, "0", Duration.ZERO, 200)).extracting(RunStreamEvent::type).containsExactly("snapshot-required");
            }
            store.complete(id + ":terminal", id, "done", "{}", "{}");
            var commands = RedisClient.create("redis://127.0.0.1:" + port);
            try (var connection = commands.connect()) {
                var events = connection.sync().xrange("mindhaven:run:" + id + ":events", Range.unbounded());
                assertThat(events).hasSize(3);
                assertThat(events.get(1).getBody().get("payload")).contains("assistant");
            } finally { commands.shutdown(); }
        }
    }
    @Test void rollbackDoesNotPublishTerminalAndForeignUserCannotReadRun() {
        var a = auth.register("owner-" + UUID.randomUUID(), "Owner", "admin", "test-password-123");
        var b = auth.register("foreign-" + UUID.randomUUID(), "Other", "admin", "test-password-123");
        String id;
        try (var scope = TenantContext.open(a.identity())) {
            id = runs.create(sessions.create().id(), UUID.randomUUID().toString(), "hash", "hi").run().id();
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                runs.finish(id, AiRun.Status.COMPLETED, "done", Map.of(), null);
                throw new IllegalStateException("rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(runs.get(id).status()).isEqualTo(AiRun.Status.QUEUED);
            assertThat(outbox.pending()).noneMatch(n -> n.runId().equals(id));
        }
        try (var scope = TenantContext.open(b.identity())) { assertThatThrownBy(() -> runs.get(id)).isInstanceOf(NoSuchElementException.class); }
    }
    @Test void trimmedOrMissingCursorRequiresSnapshot() {
        String id = UUID.randomUUID().toString(); store.initialize(id);
        String cursor = store.append(id, "delta", "{}");
        var client = RedisClient.create("redis://127.0.0.1:" + port);
        try (var connection = client.connect(); var replay = store.reader()) {
            connection.sync().xdel("mindhaven:run:" + id + ":events", cursor);
            assertThat(replay.readAfter(id, cursor, Duration.ZERO, 10)).extracting(RunStreamEvent::type).containsExactly("snapshot-required");
            connection.sync().del("mindhaven:run:" + id + ":events");
            assertThat(replay.readAfter(id, "0", Duration.ZERO, 10)).extracting(RunStreamEvent::type).containsExactly("snapshot-required");
        } finally { client.shutdown(); }
    }
}
