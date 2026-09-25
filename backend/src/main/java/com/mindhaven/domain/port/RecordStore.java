package com.mindhaven.domain.port;

import java.util.List;
import java.util.Optional;

/** Persistence port. Application services never depend on JDBC or a database dialect. */
public interface RecordStore {
  <T> Optional<T> get(String bucket, String id, Class<T> type);

  <T> List<T> list(String bucket, Class<T> type);

  void put(String bucket, String id, Object value);

  void delete(String bucket, String id);
}
