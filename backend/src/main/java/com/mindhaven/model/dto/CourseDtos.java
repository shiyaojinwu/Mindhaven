package com.mindhaven.model.dto;

import com.mindhaven.model.course.Course;
import jakarta.validation.constraints.*;

public final class CourseDtos {
    private CourseDtos() {
    }

    public record Draft(String id, Course course, int revision, int publishedRevision, String status) {
    }

    public record Input(@NotBlank @Size(max = 120) String title, @NotBlank @Size(max = 40) String category,
                        @Min(1) @Max(240) int minutes, @NotBlank @Size(max = 500) String intro,
                        @Size(max = 20000) String content, int expectedRevision, @Size(max = 36) String videoId) {
        public Input(String title, String category, int minutes, String intro, String content, int expectedRevision) {
            this(title, category, minutes, intro, content, expectedRevision, null);
        }
    }

    public record Revision(int expectedRevision) {
    }
}
