package com.mindhaven.model.course;


public record Video(String id, String filename, String contentType, long size, String createdAt, String storage,
                    String bucket, String objectKey, String location) {
}
