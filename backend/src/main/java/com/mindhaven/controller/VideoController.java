package com.mindhaven.controller;

import com.mindhaven.model.course.Video;
import com.mindhaven.model.vo.VideoConfigResponse;
import com.mindhaven.service.course.VideoService;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class VideoController {
    private final VideoService service;

    public VideoController(VideoService service) {
        this.service = service;
    }

    @GetMapping("/admin/videos/config")
    public VideoConfigResponse config() {
        return service.config();
    }

    @PostMapping(value = "/admin/videos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Video upload(@RequestParam("file") MultipartFile file) throws IOException {
        return service.upload(file);
    }

    @GetMapping("/videos/{id}")
    public void read(@PathVariable String id, @RequestHeader(value = "Range", required = false) String range, jakarta.servlet.http.HttpServletResponse response) throws IOException {
        var v = service.readable(id);
        long start = 0, end = v.size() - 1;
        boolean partial = range != null;
        if (partial) {
            try {
                var ranges = HttpRange.parseRanges(range);
                if (ranges.size() != 1) throw new IllegalArgumentException();
                start = ranges.getFirst().getRangeStart(v.size());
                end = ranges.getFirst().getRangeEnd(v.size());
                if (start < 0 || start > end || start >= v.size()) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                response.setHeader("Content-Range", "bytes */" + v.size());
                response.setStatus(416);
                return;
            }
        }
        try (var in = service.open(v, start, end)) {
            response.setStatus(partial ? 206 : 200);
            response.setContentType(v.contentType());
            response.setContentLengthLong(end - start + 1);
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("Content-Disposition", "inline; filename=\"" + id + (v.contentType().equals("video/mp4") ? ".mp4" : ".webm") + "\"");
            if (partial) response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + v.size());
            byte[] buffer = new byte[64 * 1024];
            long remaining = end - start + 1;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) throw new EOFException("Truncated video");
                response.getOutputStream().write(buffer, 0, n);
                remaining -= n;
            }
        }
    }
}
