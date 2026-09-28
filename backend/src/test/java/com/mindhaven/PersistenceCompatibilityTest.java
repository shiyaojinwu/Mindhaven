package com.mindhaven;

import com.mindhaven.integration.ai.*;
import com.mindhaven.integration.storage.*;
import com.mindhaven.integration.vector.*;
import com.mindhaven.manager.AuthManager;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.manager.RunManager;
import com.mindhaven.model.chat.AiRun;
import com.mindhaven.security.LoginIdentity;
import com.mindhaven.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "mindhaven.ai-mode=demo", "mindhaven.vector-mode=local"})
class PersistenceCompatibilityTest {
    @Autowired
    RecordManager records;
    @Autowired
    AuthManager auth;
    @Autowired
    RunManager runs;
    @Autowired
    JdbcTemplate jdbc;

    private LoginIdentity user(String tenant, String id) {
        return new LoginIdentity(tenant, tenant, "test", id, id, "ADMIN");
    }

    private String tenant() {
        String id = UUID.randomUUID().toString();
        auth.createTenant(id, id, "test", "2026-01-01");
        return id;
    }

    @Test
    void legacyCompositeKeysStayIsolatedAndUpdatesPreserveCreationTime() {
        String a = tenant(), b = tenant();
        jdbc.update("INSERT INTO tenant_records(tenant_id,owner_id,bucket,id,payload,created_at)" + " VALUES(?,?,?,?,?,?)", a, "alice", "messages", "same", "{\"value\":\"legacy\"}", "2025-01-01");
        try (var scope = TenantContext.open(user(a, "alice"))) {
            assertThat(records.get("messages", "same", Map.class).orElseThrow()).containsEntry("value", "legacy");
            records.put("messages", "same", Map.of("value", "updated"));
            records.put("courses", "same", Map.of("value", "shared"));
        }
        try (var scope = TenantContext.open(user(a, "bob"))) {
            assertThat(records.get("messages", "same", Map.class)).isEmpty();
            records.put("messages", "same", Map.of("value", "bob"));
            assertThat(records.get("courses", "same", Map.class)).isPresent();
            records.delete("messages", "same");
        }
        try (var scope = TenantContext.open(user(b, "alice"))) {
            assertThat(records.get("courses", "same", Map.class)).isEmpty();
            records.delete("messages", "same");
        }
        try (var scope = TenantContext.open(user(a, "alice"))) {
            assertThat(records.get("messages", "same", Map.class).orElseThrow()).containsEntry("value", "updated");
        }
        assertThat(jdbc.queryForObject("SELECT created_at FROM tenant_records WHERE tenant_id=? AND owner_id=? AND" + " bucket=? AND id=?", String.class, a, "alice", "messages", "same")).isEqualTo("2025-01-01");
    }

    @Test
    void eventInsertFailureRollsBackSequenceAndTerminalStatus() {
        try (var scope = TenantContext.open(user(tenant(), "alice"))) {
            String id = runs.create("s", "r", "hash", "hello").run().id();
            assertThat(runs.start(id)).isTrue();
            assertThatThrownBy(() -> runs.append(id, null, Map.of())).isInstanceOf(RuntimeException.class);
            runs.append(id, "delta", Map.of("text", "hello"));
            assertThat(runs.events(id, 0)).extracting(AiRun.Event::seq).containsExactly(1L);
            assertThatThrownBy(() -> runs.finish(id, AiRun.Status.COMPLETED, null, Map.of(), null)).isInstanceOf(RuntimeException.class);
            assertThat(runs.get(id).status()).isEqualTo(AiRun.Status.RUNNING);
            runs.finish(id, AiRun.Status.COMPLETED, "done", Map.of(), null);
            assertThat(runs.events(id, 0)).extracting(AiRun.Event::seq).containsExactly(1L, 2L);
        }
    }
}
