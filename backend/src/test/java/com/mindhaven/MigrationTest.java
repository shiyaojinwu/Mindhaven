package com.mindhaven;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

class MigrationTest {
  @TempDir Path directory;

  @Test
  void upgradesAnExistingDatabaseWithoutReplacingItsRecords() {
    var ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + directory.resolve("existing.db"));
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__existing_records.sql"))
        .execute(ds);
    var jdbc = new JdbcTemplate(ds);
    jdbc.update("INSERT INTO tenants VALUES('tenant','slug','Existing tenant','2025-01-01')");
    jdbc.update(
        "INSERT INTO tenant_records"
            + " VALUES('tenant','owner','messages:session','message','{\"content\":\"原有私密对话\"}','2025-01-01')");
    var before = jdbc.queryForList("SELECT * FROM tenant_records");
    var flyway =
        Flyway.configure().dataSource(ds).baselineOnMigrate(true).baselineVersion("0").load();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT * FROM tenant_records")).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_runs", Integer.class)).isZero();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }
}
