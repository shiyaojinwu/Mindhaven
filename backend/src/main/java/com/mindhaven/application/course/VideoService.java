package com.mindhaven.application.course;

import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.domain.port.MediaStorage;
import com.mindhaven.domain.port.RecordStore;
import com.mindhaven.security.TenantContext;
import java.io.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

@org.springframework.stereotype.Service
public class VideoService {
  private final RecordStore store;
  private final MediaStorage storage;
  private final long limit;

  public VideoService(
      RecordStore store,
      MediaStorage storage,
      @Value("${mindhaven.video-max-size:512MB}") DataSize limit) {
    this.store = store;
    this.storage = storage;
    this.limit = limit.toBytes();
  }

  public Object config() {
    TenantContext.requireAdmin();
    return Map.of("maxBytes", limit, "formats", List.of("mp4", "webm"), "storage", storage.mode());
  }

  public Video upload(MultipartFile file) throws IOException {
    TenantContext.requireAdmin();
    if (file.isEmpty()) throw new IllegalArgumentException("请选择非空视频文件");
    if (file.getSize() > limit) throw new HttpProblem(413, "视频超过上传大小限制");
    String name = Optional.ofNullable(file.getOriginalFilename()).orElse("video");
    String lower = name.toLowerCase(Locale.ROOT);
    byte[] header;
    try (var in = file.getInputStream()) {
      header = in.readNBytes(16);
    }
    boolean mp4 =
        lower.endsWith(".mp4")
            && header.length >= 12
            && header[4] == 'f'
            && header[5] == 't'
            && header[6] == 'y'
            && header[7] == 'p';
    boolean webm =
        lower.endsWith(".webm")
            && header.length >= 4
            && (header[0] & 255) == 0x1a
            && (header[1] & 255) == 0x45
            && (header[2] & 255) == 0xdf
            && (header[3] & 255) == 0xa3;
    if (!mp4 && !webm) throw new IllegalArgumentException("仅支持 MP4 / WebM 视频，文件内容与格式必须匹配");
    String id = UUID.randomUUID().toString();
    String tenant = TenantContext.require().tenantId();
    var v =
        storage.upload(
            tenant,
            id,
            name.replace('\\', '/').substring(name.replace('\\', '/').lastIndexOf('/') + 1),
            mp4 ? "video/mp4" : "video/webm",
            file);
    try {
      store.put("videos", id, v);
      return v;
    } catch (RuntimeException e) {
      try {
        storage.delete(v, tenant);
      } catch (Exception cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    }
  }

  public Video readable(String id) {
    var identity = TenantContext.require();
    var v =
        store.get("videos", id, Video.class).orElseThrow(() -> new NoSuchElementException("视频不存在"));
    if (!identity.admin()
        && store.list("courses", Course.class).stream().noneMatch(c -> id.equals(c.videoId())))
      throw new NoSuchElementException("课程视频尚未发布或已停用");
    if (!id.matches("[0-9a-f-]{36}")) throw new NoSuchElementException("视频不存在");
    return v;
  }

  public InputStream open(Video v, long start, long end) throws IOException {
    return storage.open(v, TenantContext.require().tenantId(), start, end);
  }
}
