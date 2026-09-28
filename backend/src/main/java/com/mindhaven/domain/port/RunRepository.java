package com.mindhaven.domain.port;

import com.mindhaven.domain.model.AiRun;
import java.util.List;

/** Every user-facing operation is scoped to the authenticated tenant and user. */
public interface RunRepository {
  AiRun.Created create(String sessionId, String requestId, String requestHash, String message);

  AiRun get(String id);

  List<AiRun> recent(String sessionId);

  boolean start(String id);

  void requestCancel(String id);

  void append(String id, String name, Object payload);

  List<AiRun.Event> events(String id, long after);

  void finish(String id, AiRun.Status status, String name, Object payload, String error);

  /** Local worker recovery. The embedded runtime is deployed as one process per database. */
  void recoverInterrupted();
}
