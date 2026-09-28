package com.mindhaven.service.course;

import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.model.course.Course;
import com.mindhaven.model.course.Video;
import com.mindhaven.model.dto.CourseDtos.*;
import com.mindhaven.security.TenantContext;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class CourseService {
    private final RecordManager store;
    private final TransactionTemplate tx;

    public CourseService(RecordManager store, TransactionTemplate tx) {
        this.store = store;
        this.tx = tx;
    }

    private Draft get(String id) {
        return store.get("course-drafts", id, Draft.class).orElseGet(() -> store.get("courses", id, Course.class).map(c -> new Draft(id, c, 0, 0, "PUBLISHED")).orElseThrow(() -> new NoSuchElementException("课程不存在")));
    }

    private void check(Draft d, int revision) {
        if (d.revision() != revision) throw new HttpProblem(409, "课程已被修改，请返回列表重新加载");
    }

    public List<Draft> list() {
        TenantContext.requireAdmin();
        Map<String, Draft> result = new LinkedHashMap<>();
        for (var c : store.list("courses", Course.class))
            result.put(c.id(), new Draft(c.id(), c, 0, 0, "PUBLISHED"));
        for (var d : store.list("course-drafts", Draft.class)) result.put(d.id(), d);
        return new ArrayList<>(result.values()).reversed();
    }

    public Draft create(Input i) {
        return save(null, i);
    }

    public synchronized Draft save(String id, Input i) {
        TenantContext.requireAdmin();
        return tx.execute(t -> {
            String videoId = i.videoId() == null || i.videoId().isBlank() ? null : i.videoId();
            String content = i.content() == null ? "" : i.content().strip();
            if (content.isEmpty() && videoId == null) throw new IllegalArgumentException("请填写正文或上传课程视频");
            if (videoId != null)
                store.get("videos", videoId, Video.class).orElseThrow(() -> new NoSuchElementException("视频不存在或不属于本机构"));
            var old = id == null ? null : get(id);
            if (old != null) check(old, i.expectedRevision());
            String key = old == null ? UUID.randomUUID().toString() : id;
            var c = new Course(key, i.title().strip(), i.category().strip(), i.minutes(), i.intro().strip(), content, videoId);
            var d = new Draft(key, c, old == null ? 1 : old.revision() + 1, old == null ? 0 : old.publishedRevision(), old == null ? "DRAFT" : old.status());
            store.put("course-drafts", key, d);
            return d;
        });
    }

    public synchronized Draft publish(String id, Revision i) {
        return state(id, i, false);
    }

    public synchronized Draft archive(String id, Revision i) {
        return state(id, i, true);
    }

    private Draft state(String id, Revision i, boolean archive) {
        TenantContext.requireAdmin();
        return tx.execute(t -> {
            var old = get(id);
            check(old, i.expectedRevision());
            int revision = old.revision() + 1;
            var d = new Draft(id, old.course(), revision, archive ? old.publishedRevision() : revision, archive ? "ARCHIVED" : "PUBLISHED");
            if (archive) store.delete("courses", id);
            else store.put("courses", id, d.course());
            store.put("course-drafts", id, d);
            return d;
        });
    }
}
