package com.mindhaven.model.course;


public record Course(String id, String title, String category, int minutes, String intro, String content,
                     String videoId) {
    public Course(String id, String title, String category, int minutes, String intro, String content) {
        this(id, title, category, minutes, intro, content, null);
    }
}
