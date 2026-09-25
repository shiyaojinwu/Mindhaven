package com.mindhaven.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.domain.port.RecordStore;
import com.mindhaven.security.TenantContext;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRecordStore implements RecordStore {
  private static final Set<String> SHARED =
      Set.of(
          "knowledge",
          "courses",
          "course-drafts",
          "videos",
          "survey-drafts",
          "survey-versions",
          "survey-responses");
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcRecordStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  private String owner(String bucket) {
    return SHARED.contains(bucket) ? "" : TenantContext.require().userId();
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("Stored record cannot be decoded", e);
    }
  }

  public <T> Optional<T> get(String bucket, String id, Class<T> type) {
    var identity = TenantContext.require();
    return jdbc
        .query(
            "SELECT payload FROM tenant_records WHERE tenant_id=? AND owner_id=? AND bucket=? AND"
                + " id=?",
            (rs, n) -> read(rs.getString(1), type),
            identity.tenantId(),
            owner(bucket),
            bucket,
            id)
        .stream()
        .findFirst();
  }

  public <T> List<T> list(String bucket, Class<T> type) {
    var identity = TenantContext.require();
    return jdbc.query(
        "SELECT payload FROM tenant_records WHERE tenant_id=? AND owner_id=? AND bucket=? ORDER BY"
            + " created_at,id",
        (rs, n) -> read(rs.getString(1), type),
        identity.tenantId(),
        owner(bucket),
        bucket);
  }

  @Transactional
  public void put(String bucket, String id, Object value) {
    var identity = TenantContext.require();
    String owner = owner(bucket);
    try {
      String json = mapper.writeValueAsString(value);
      if (jdbc.update(
              "UPDATE tenant_records SET payload=? WHERE tenant_id=? AND owner_id=? AND bucket=?"
                  + " AND id=?",
              json,
              identity.tenantId(),
              owner,
              bucket,
              id)
          == 0)
        jdbc.update(
            "INSERT INTO tenant_records(tenant_id,owner_id,bucket,id,payload,created_at)"
                + " VALUES(?,?,?,?,?,?)",
            identity.tenantId(),
            owner,
            bucket,
            id,
            json,
            Instant.now().toString());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public void delete(String bucket, String id) {
    var identity = TenantContext.require();
    jdbc.update(
        "DELETE FROM tenant_records WHERE tenant_id=? AND owner_id=? AND bucket=? AND id=?",
        identity.tenantId(),
        owner(bucket),
        bucket,
        id);
  }
}
