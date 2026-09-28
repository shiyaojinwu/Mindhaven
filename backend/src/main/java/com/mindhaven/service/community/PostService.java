package com.mindhaven.service.community;

import com.mindhaven.common.Times;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.model.community.Post;
import com.mindhaven.model.course.Progress;
import com.mindhaven.model.dto.PostInput;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PostService {
    private final RecordManager store;

    public PostService(RecordManager store) {
        this.store = store;
    }

    public List<Post> posts() {
        return store.list("posts", Post.class).reversed();
    }

    public Post post(PostInput input) {
        var p = new Post(UUID.randomUUID().toString(), input.content(), input.mood(), 0, Times.now());
        store.put("posts", p.id(), p);
        return p;
    }

    public synchronized Post hug(String id) {
        var p = store.get("posts", id, Post.class).orElseThrow(() -> new NoSuchElementException("记录不存在"));
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
