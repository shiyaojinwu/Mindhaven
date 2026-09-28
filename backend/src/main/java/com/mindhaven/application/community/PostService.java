package com.mindhaven.application.community;

import com.mindhaven.application.dto.PostInput;
import com.mindhaven.common.Times;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.RecordStore;
import java.util.*;

@org.springframework.stereotype.Service
public class PostService {
  private final RecordStore store;

  public PostService(RecordStore store) {
    this.store = store;
  }

  public Object posts() {
    return store.list("posts", Post.class).reversed();
  }

  public Object post(PostInput input) {
    var p = new Post(UUID.randomUUID().toString(), input.content(), input.mood(), 0, Times.now());
    store.put("posts", p.id(), p);
    return p;
  }

  public synchronized Object hug(String id) {
    var p =
        store.get("posts", id, Post.class).orElseThrow(() -> new NoSuchElementException("记录不存在"));
    String marker = "local:" + id;
    if (store.get("hugs", marker, Progress.class).isPresent()) return p;
    var next = new Post(id, p.content(), p.mood(), p.hugs() + 1, p.createdAt());
    store.put("posts", id, next);
    store.put("hugs", marker, new Progress(marker, Times.now()));
    return next;
  }

  public void delete(String id) {
    store.delete("posts", id);
  }
}
