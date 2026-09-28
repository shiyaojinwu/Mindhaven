package com.mindhaven.integration.storage;

import com.mindhaven.model.course.Video;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;

/**
 * Video storage boundary; implementations own filesystem and object-store details.
 */
public interface MediaStorage {
    String mode();

    Video upload(String tenant, String id, String name, String type, MultipartFile file) throws IOException;

    InputStream open(Video video, String tenant, long start, long end) throws IOException;

    void delete(Video video, String tenant) throws IOException;
}
