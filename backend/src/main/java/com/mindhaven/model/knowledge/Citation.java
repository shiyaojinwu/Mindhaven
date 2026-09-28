package com.mindhaven.model.knowledge;


public record Citation(String id, String title, String topic, String version, String sourceUrl, String text,
                       double score) {
}
