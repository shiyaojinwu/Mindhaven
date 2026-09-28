package com.mindhaven.service.course;

import com.mindhaven.common.Times;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.model.course.Course;
import com.mindhaven.model.course.Progress;
import com.mindhaven.model.questionnaire.Assessment;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class LearningService {
    private final RecordManager store;

    public LearningService(RecordManager store) {
        this.store = store;
    }

    public List<Course> courses() {
        return store.list("courses", Course.class);
    }

    public List<Progress> progress() {
        return store.list("progress", Progress.class);
    }

    public Progress complete(String id) {
        store.get("courses", id, Course.class).orElseThrow(() -> new NoSuchElementException("课程不存在"));
        var p = new Progress(id, Times.now());
        store.put("progress", id, p);
        return p;
    }

    public List<Assessment> reports() {
        return store.list("assessments", Assessment.class).reversed();
    }
}
