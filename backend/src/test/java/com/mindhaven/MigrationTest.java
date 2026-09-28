package com.mindhaven;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class MigrationTest {
    @TempDir
    Path directory;

    private SQLiteDataSource legacy(String name) {
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + directory.resolve(name));
        ds.setEnforceForeignKeys(true);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__existing_records.sql")).execute(ds);
        return ds;
    }

    private Flyway flyway(SQLiteDataSource ds) {
        return Flyway.configure().dataSource(ds).baselineOnMigrate(true).baselineVersion("0").load();
    }

    @Test
    void upgradesAnExistingDatabaseWithoutReplacingItsRecords() {
        var ds = legacy("existing.db");
        var jdbc = new JdbcTemplate(ds);
        // Identical business IDs can belong to different tenants and different users.
        for (String tenant : new String[]{"tenant-a", "tenant-b"}) {
            jdbc.update("INSERT INTO tenants VALUES(?,?,?,?)", tenant, tenant, tenant, "2025-01-01");
            for (String owner : new String[]{"alice", "bob"}) {
                jdbc.update("INSERT INTO tenant_records VALUES(?,?,?,?,?,?)", tenant, owner, "sessions", "session", """
                        {"id":"session","title":"旧对话","createdAt":"2025-01-01"}
                        """, "2025-01-01");
                jdbc.update("INSERT INTO tenant_records VALUES(?,?,?,?,?,?)", tenant, owner, "messages:session", "message", """
                        {"id":"message","sessionId":"session","seq":1,"role":"assistant","content":"原有私密对话",
                         "createdAt":"2025-01-01","citations":[{"id":"knowledge","text":"引用快照"}],"status":"complete",
                         "citationCheck":{"status":"VALID","citedIds":["knowledge"],"invalidIds":[]}}
                        """, "2025-01-01");
            }
            jdbc.update("INSERT INTO tenant_records VALUES(?,?,?,?,?,?)", tenant, "", "knowledge", "knowledge", """
                    {"id":"knowledge","title":"旧知识","topic":"睡眠","version":"v1","sourceUrl":"","text":"原文"}
                    """, "2025-01-01");
        }
        var before = jdbc.queryForList("SELECT * FROM tenant_records");
        var flyway = flyway(ds);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT * FROM tenant_records")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_session", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk", Integer.class)).isEqualTo(2);
        var migrated = jdbc.queryForMap("SELECT * FROM chat_message WHERE tenant_id='tenant-a' AND owner_id='alice'");
        assertThat(migrated).containsEntry("id", "message").containsEntry("content", "原有私密对话").containsEntry("created_at", "2025-01-01");
        assertThat(migrated.get("citations_json").toString()).contains("引用快照");
        assertThat(migrated.get("citation_check_json").toString()).contains("VALID");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void invalidLegacyDataStopsMigrationWithoutDeletingOrPartiallyCopyingRecords() {
        var ds = legacy("invalid.db");
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO tenants VALUES('tenant','slug','Existing tenant','2025-01-01')");
        jdbc.update("INSERT INTO tenant_records VALUES('tenant','owner','sessions','s','{}','2025-01-01')");
        assertThatThrownBy(() -> flyway(ds).migrate()).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT payload FROM tenant_records", String.class)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='chat_session'", Integer.class)).isZero();
    }
}
