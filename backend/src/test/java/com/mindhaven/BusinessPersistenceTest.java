package com.mindhaven;

import com.mindhaven.manager.*;
import com.mindhaven.service.chat.ContextPlanner;
import com.mindhaven.config.Settings;
import org.springframework.ai.chat.messages.AssistantMessage;
import com.mindhaven.model.chat.Session;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.CitationCheck;
import com.mindhaven.model.knowledge.Knowledge;
import com.mindhaven.model.knowledge.Citation;
import com.mindhaven.security.LoginIdentity;
import com.mindhaven.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "mindhaven.ai-mode=demo", "mindhaven.vector-mode=local"})
class BusinessPersistenceTest {
    @Autowired
    ContextPlanner planner;
    @Autowired
    Settings settings;
    @Autowired
    SessionManager sessions;
    @Autowired
    MessageManager messages;
    @Autowired
    KnowledgeManager knowledge;
    @Autowired
    AuthManager auth;
    @Autowired
    RecordManager records;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactions;

    private String tenant() {
        String id = UUID.randomUUID().toString();
        auth.createTenant(id, id, "test", "2025-01-01");
        return id;
    }

    private LoginIdentity identity(String tenant, String owner) {
        return new LoginIdentity(tenant, tenant, "test", owner, owner, "ADMIN");
    }

    @Test
    void scopedTablesPreserveSnapshotsOrderingAndTenantSharedKnowledge() {
        String a = tenant(), b = tenant();
        var check = new CitationCheck(CitationCheck.Status.VALID, List.of("k"), List.of());
        var citations = List.of(new Citation("k", "title", "睡眠", "v1", "", "历史原文", 0.8));
        try (var scope = TenantContext.open(identity(a, "alice"))) {
            sessions.save(new Session("s", "旧标题", "2025-01-01"));
            sessions.save(new Session("s", "新标题", "2026-01-01"));
            messages.save(new ChatMessage("m2", "s", 2, "assistant", "回复", "2025-01-01", citations, "complete", check));
            messages.save(new ChatMessage("m1", "s", 1, "user", "问题", "2025-01-01", List.of(), "pending"));
            messages.save(new ChatMessage("m1", "s", 1, "user", "问题", "2025-01-01", List.of(), "complete"));
            knowledge.save(new Knowledge("k", "title", "睡眠", "v1", "", "当前原文"));
            assertThat(sessions.get("s").orElseThrow()).isEqualTo(new Session("s", "新标题", "2025-01-01"));
            assertThat(messages.completeAfter("s", 1)).extracting(ChatMessage::id).containsExactly("m2");
            assertThat(messages.maxSequence("s")).isEqualTo(2);
            assertThat(messages.list("s")).extracting(ChatMessage::id).containsExactly("m1", "m2");
            assertThat(messages.get("m2").orElseThrow().citations()).isEqualTo(citations);
            assertThat(messages.get("m2").orElseThrow().citationCheck()).isEqualTo(check);
            assertThat(messages.get("m1").orElseThrow().citationCheck()).isNull();
            assertThatThrownBy(() -> records.put("sessions", "s", Map.of())).isInstanceOf(IllegalArgumentException.class);
        }
        try (var scope = TenantContext.open(identity(a, "bob"))) {
            assertThat(sessions.get("s")).isEmpty();
            assertThat(messages.list("s")).isEmpty();
            assertThat(knowledge.get("k")).isPresent();
            sessions.save(new Session("s", "Bob", "2025-01-01"));
            messages.save(new ChatMessage("m1", "s", 1, "user", "Bob", "2025-01-01", List.of(), "pending"));
        }
        try (var scope = TenantContext.open(identity(b, "alice"))) {
            assertThat(sessions.get("s")).isEmpty();
            assertThat(messages.get("m1")).isEmpty();
            assertThat(knowledge.get("k")).isEmpty();
            knowledge.save(new Knowledge("k", "B", "睡眠", "v1", "", "B"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_records WHERE tenant_id=?", Integer.class, a)).isZero();
    }

    @Test
    void messageConstraintsAndTransactionRollbackProtectHistory() {
        try (var scope = TenantContext.open(identity(tenant(), "alice"))) {
            sessions.save(new Session("s", "title", "2025-01-01"));
            messages.save(new ChatMessage("m", "s", 1, "user", "hello", "2025-01-01", List.of(), "pending"));
            assertThatThrownBy(() -> messages.save(new ChatMessage("duplicate", "s", 1, "user", "hello", "2025-01-01", List.of(), "complete"))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                messages.save(new ChatMessage("m", "s", 1, "user", "hello", "2025-01-01", List.of(), "complete"));
                messages.save(new ChatMessage("orphan", "missing", 2, "assistant", "answer", "2025-01-01", List.of(), "complete"));
            })).isInstanceOf(RuntimeException.class);
            assertThat(messages.get("m").orElseThrow().status()).isEqualTo("pending");
            assertThat(messages.list("s")).hasSize(1);
        }
    }
    @Test
    void partialReplySurvivesDatabaseReadAndAppearsInContinuationContext() {
        String t = tenant();
        try (var scope = TenantContext.open(identity(t, "alice"))) {
            sessions.save(new Session("continue", "长文", "2026-01-01"));
            messages.save(new ChatMessage("u", "continue", 1, "user", "写六部分", "2026-01-01", List.of(), "complete"));
            messages.save(new ChatMessage("a", "continue", 2, "assistant", "第四部分：独特的续写末尾", "2026-01-01", List.of(), "partial"));
            messages.save(new ChatMessage("failed", "continue", 3, "user", "不应出现", "2026-01-01", List.of(), "failed"));
            var history = messages.completeAfter("continue", 0);
            assertThat(history).extracting(ChatMessage::id).containsExactly("u", "a");
            var plan = planner.plan(history, null, "继续", List.of(), settings);
            assertThat(plan.messages().stream().filter(m -> m instanceof AssistantMessage)
                    .map(m -> m.getText()).toList()).singleElement()
                    .asString().contains("独特的续写末尾", "仅部分完成", "不重复已完成部分");
            assertThat(messages.list("continue").get(1).status()).isEqualTo("partial");
            assertThat(messages.completeAfter("continue", 2)).isEmpty();
        }
        try (var scope = TenantContext.open(identity(t, "bob"))) {
            assertThat(messages.completeAfter("continue", 0)).isEmpty();
        }
    }

}
