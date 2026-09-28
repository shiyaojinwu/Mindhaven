package com.mindhaven.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.domain.port.RecordStore;
import com.mindhaven.infrastructure.persistence.mapper.RecordMapper;
import com.mindhaven.security.TenantContext;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisRecordStore implements RecordStore {
  private static final Set<String> SHARED =
      Set.of(
          "knowledge",
          "courses",
          "course-drafts",
          "videos",
          "survey-drafts",
          "survey-versions",
          "survey-responses");
  private final RecordMapper records;
  private final ObjectMapper mapper;

  public MybatisRecordStore(RecordMapper records, ObjectMapper mapper) {
    this.records = records;
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
    return Optional.ofNullable(records.get(identity.tenantId(), owner(bucket), bucket, id))
        .map(value -> read(value, type));
  }

  public <T> List<T> list(String bucket, Class<T> type) {
    var identity = TenantContext.require();
    return records.list(identity.tenantId(), owner(bucket), bucket).stream()
        .map(value -> read(value, type))
        .toList();
  }

  @Transactional
  public void put(String bucket, String id, Object value) {
    var identity = TenantContext.require();
    String owner = owner(bucket);
    try {
      String json = mapper.writeValueAsString(value);
      records.put(identity.tenantId(), owner, bucket, id, json, Instant.now().toString());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public void delete(String bucket, String id) {
    var identity = TenantContext.require();
    records.delete(identity.tenantId(), owner(bucket), bucket, id);
  }
}
