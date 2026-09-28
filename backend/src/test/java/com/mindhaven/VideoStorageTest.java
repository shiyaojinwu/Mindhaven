package com.mindhaven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.integration.storage.VideoStorage;
import com.mindhaven.model.course.Video;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class VideoStorageTest {
    @Test
    void s3SignedUploadRangedReadDeleteAndLocalCompatibility() throws Exception {
        var objects = new ConcurrentHashMap<String, byte[]>();
        var authorization = new AtomicReference<String>();
        var range = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try {
                authorization.set(ex.getRequestHeaders().getFirst("Authorization"));
                String key = ex.getRequestURI().getPath();
                switch (ex.getRequestMethod()) {
                    case "PUT" -> {
                        byte[] body = ex.getRequestBody().readAllBytes();
                        if ("aws-chunked".equals(ex.getRequestHeaders().getFirst("Content-Encoding"))) {
                            var raw = new ByteArrayInputStream(body);
                            var decoded = new ByteArrayOutputStream();
                            while (true) {
                                var line = new StringBuilder();
                                int ch;
                                while ((ch = raw.read()) != -1 && ch != '\n') if (ch != '\r') line.append((char) ch);
                                int size = Integer.parseInt(line.toString().split(";")[0], 16);
                                if (size == 0) break;
                                decoded.write(raw.readNBytes(size));
                                raw.skipNBytes(2);
                            }
                            body = decoded.toByteArray();
                        }
                        objects.put(key, body);
                        ex.getResponseHeaders().add("ETag", "\"fixture\"");
                        ex.sendResponseHeaders(200, -1);
                    }
                    case "GET" -> {
                        String value = ex.getRequestHeaders().getFirst("Range");
                        range.set(value);
                        String[] bounds = value.substring(6).split("-");
                        int start = Integer.parseInt(bounds[0]), end = Integer.parseInt(bounds[1]);
                        byte[] all = objects.get(key), bytes = Arrays.copyOfRange(all, start, end + 1);
                        ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + all.length);
                        ex.sendResponseHeaders(206, bytes.length);
                        ex.getResponseBody().write(bytes);
                    }
                    case "DELETE" -> {
                        objects.remove(key);
                        ex.sendResponseHeaders(204, -1);
                    }
                    default -> ex.sendResponseHeaders(405, -1);
                }
            } finally {
                ex.close();
            }
        });
        server.start();
        Path dir = Files.createTempDirectory("storage-test-");
        var env = new MockEnvironment().withProperty("mindhaven.media-storage", "s3").withProperty("mindhaven.media-dir", dir.toString()).withProperty("mindhaven.s3.endpoint", "http://127.0.0.1:" + server.getAddress().getPort()).withProperty("mindhaven.s3.bucket", "test-bucket").withProperty("mindhaven.s3.access-key", "fixture-access").withProperty("mindhaven.s3.secret-key", "fixture-secret");
        var storage = new VideoStorage(env);
        try {
            byte[] bytes = "abcdefghijklmnopqrstuvwxyz".getBytes();
            var file = new MockMultipartFile("file", "clip.mp4", "video/mp4", bytes);
            Video v = storage.upload("tenant", "video", "clip.mp4", "video/mp4", file);
            assertThat(v.storage()).isEqualTo("s3");
            assertThat(objects.get("/test-bucket/tenant/video")).isEqualTo(bytes);
            assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256 ");
            try (var in = storage.open(v, "tenant", 4, 9)) {
                assertThat(in.readAllBytes()).isEqualTo("efghij".getBytes());
            }
            assertThat(range.get()).isEqualTo("bytes=4-9");
            assertThatThrownBy(() -> storage.open(v, "other-tenant", 0, 5)).isInstanceOf(HttpProblem.class);
            // Switching writes back to local keeps configured S3 reads available.
            env.setProperty("mindhaven.media-storage", "local");
            var localDefault = new VideoStorage(env);
            try {
                try (var in = localDefault.open(v, "tenant", 0, 2)) {
                    assertThat(in.readAllBytes()).isEqualTo("abc".getBytes());
                }
                var local = localDefault.upload("tenant", "local", "clip.mp4", "video/mp4", file);
                try (var in = storage.open(local, "tenant", 0, 25)) {
                    assertThat(in.readAllBytes()).isEqualTo(bytes);
                }
                // Old JSON has no storage pointer: remains local after enabling S3.
                var legacy = new ObjectMapper().readValue("{\"id\":\"local\",\"filename\":\"clip.mp4\",\"contentType\":\"video/mp4\",\"size\":26,\"createdAt\":\"now\"}", Video.class);
                try (var in = storage.open(legacy, "tenant", 0, 25)) {
                    assertThat(in.readAllBytes()).isEqualTo(bytes);
                }
                localDefault.delete(local, "tenant");
            } finally {
                localDefault.close();
            }
            var changed = new Video(v.id(), v.filename(), v.contentType(), v.size(), v.createdAt(), "s3", v.bucket(), v.objectKey(), "different-endpoint");
            assertThatThrownBy(() -> storage.open(changed, "tenant", 0, 2)).isInstanceOf(HttpProblem.class);
            storage.delete(v, "tenant");
            assertThat(objects).isEmpty();
        } finally {
            storage.close();
            server.stop(0);
            try (var paths = Files.walk(dir)) {
                for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void rejectsIncompleteConfig() {
        assertThatThrownBy(() -> new VideoStorage(new MockEnvironment().withProperty("mindhaven.media-storage", "s3"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VideoStorage(new MockEnvironment().withProperty("mindhaven.media-storage", "unknown"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VideoStorage(new MockEnvironment().withProperty("mindhaven.s3.bucket", "bucket").withProperty("mindhaven.s3.access-key", "only-key"))).isInstanceOf(IllegalArgumentException.class);
    }
}
