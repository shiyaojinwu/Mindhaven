package com.mindhaven.application.course;

import com.mindhaven.application.chat.ChatService;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.RecordStore;
import java.util.*;

@org.springframework.stereotype.Service
public class LearningService {
  private final RecordStore store;

  public LearningService(RecordStore store) {
    this.store = store;
  }

  public Object courses() {
    return store.list("courses", Course.class);
  }

  public Object progress() {
    return store.list("progress", Progress.class);
  }

  public Object complete(String id) {
    store.get("courses", id, Course.class).orElseThrow(() -> new NoSuchElementException("课程不存在"));
    var p = new Progress(id, ChatService.now());
    store.put("progress", id, p);
    return p;
  }

  public Object reports() {
    return store.list("assessments", Assessment.class).reversed();
  }
}
