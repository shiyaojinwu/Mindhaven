package com.mindhaven.integration.storage;

import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.model.course.Video;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Storage pointers are persisted per upload; changing the write default never reinterprets old
 * files.
 */
@Component
public class VideoStorage implements MediaStorage {
    private final Path root;
    private final String mode, bucket, location;
    private final S3Client s3;

    public VideoStorage(Environment env) {
        root = Path.of(env.getProperty("mindhaven.media-dir", "./media")).toAbsolutePath().normalize();
        mode = env.getProperty("mindhaven.media-storage", "local");
        if (!Set.of("local", "s3").contains(mode))
            throw new IllegalArgumentException("MEDIA_STORAGE must be local or s3");
        bucket = env.getProperty("mindhaven.s3.bucket", "").trim();
        String endpoint = env.getProperty("mindhaven.s3.endpoint", "").trim();
        String region = env.getProperty("mindhaven.s3.region", "us-east-1");
        location = (endpoint.isEmpty() ? "aws" : endpoint.replaceAll("/+$", "")) + "|" + region;
        if (mode.equals("s3") && bucket.isEmpty()) throw new IllegalArgumentException("S3_BUCKET is required");
        if (bucket.isEmpty()) {
            s3 = null;
            return;
        }
        var builder = S3Client.builder().region(Region.of(region)).forcePathStyle(env.getProperty("mindhaven.s3.path-style", Boolean.class, true)).httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(60))).overrideConfiguration(c -> c.apiCallTimeout(Duration.ofMinutes(30)).apiCallAttemptTimeout(Duration.ofMinutes(20)));
        if (!endpoint.isEmpty()) {
            URI uri = URI.create(endpoint);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("S3_ENDPOINT must be an HTTP(S) endpoint without credentials");
            builder.endpointOverride(uri);
        }
        String access = env.getProperty("mindhaven.s3.access-key", "");
        String secret = env.getProperty("mindhaven.s3.secret-key", "");
        if (access.isBlank() != secret.isBlank())
            throw new IllegalArgumentException("Set both S3_ACCESS_KEY and S3_SECRET_KEY");
        builder.credentialsProvider(access.isBlank() ? DefaultCredentialsProvider.builder().build() : StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)));
        s3 = builder.build();
    }

    public String mode() {
        return mode;
    }

    public Video upload(String tenant, String id, String name, String type, MultipartFile file) throws IOException {
        String key = tenant + "/" + id;
        var v = new Video(id, name, type, file.getSize(), Instant.now().toString(), mode, mode.equals("s3") ? bucket : null, key, mode.equals("s3") ? location : null);
        try (var in = file.getInputStream()) {
            if (mode.equals("local")) {
                Path path = localPath(key);
                Files.createDirectories(path.getParent());
                Files.copy(in, path);
            } else {
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).build(), RequestBody.fromInputStream(in, file.getSize()));
            }
        } catch (Exception e) {
            try {
                delete(v, tenant);
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return v;
    }

    public InputStream open(Video v, String tenant, long start, long end) throws IOException {
        String key = key(v, tenant);
        if (v.storage() == null || v.storage().equals("local")) {
            Path path = localPath(key);
            if (!Files.isRegularFile(path)) throw new NoSuchElementException("视频文件不存在，请联系管理员");
            var in = Files.newInputStream(path);
            try {
                in.skipNBytes(start);
                return in;
            } catch (Exception e) {
                in.close();
                throw e;
            }
        }
        requireS3(v);
        try {
            return s3.getObject(GetObjectRequest.builder().bucket(v.bucket()).key(key).range("bytes=" + start + "-" + end).build());
        } catch (NoSuchKeyException e) {
            throw new NoSuchElementException("视频文件不存在，请联系管理员");
        } catch (RuntimeException e) {
            throw new HttpProblem(502, "对象存储读取失败，请检查配置与服务状态");
        }
    }

    public void delete(Video v, String tenant) throws IOException {
        String key = key(v, tenant);
        if (v.storage() == null || v.storage().equals("local")) Files.deleteIfExists(localPath(key));
        else {
            requireS3(v);
            s3.deleteObject(DeleteObjectRequest.builder().bucket(v.bucket()).key(key).build());
        }
    }

    private String key(Video v, String tenant) {
        String expected = tenant + "/" + v.id();
        if (v.objectKey() != null && !v.objectKey().equals(expected)) throw new HttpProblem(500, "视频存储信息异常");
        return expected;
    }

    private Path localPath(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid media path");
        return path;
    }

    private void requireS3(Video v) {
        if (!"s3".equals(v.storage()) || s3 == null || !location.equals(v.location()))
            throw new HttpProblem(503, "该视频的对象存储未配置，请恢复原存储连接");
    }

    @PreDestroy
    public void close() {
        if (s3 != null) s3.close();
    }
}
