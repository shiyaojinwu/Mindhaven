package com.mindhaven.model.chat;


public record Summary(String id, int version, long coveredThroughSeq, String content, String updatedAt) {
}
