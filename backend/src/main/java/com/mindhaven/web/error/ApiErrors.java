package com.mindhaven.web.error;

import com.mindhaven.common.error.HttpProblem;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<?> uploadTooLarge(Exception e) {
    return ResponseEntity.status(413).body(Map.of("message", "视频超过上传大小限制，请压缩后重试"));
  }

  @ExceptionHandler(HttpProblem.class)
  ResponseEntity<?> problem(HttpProblem e) {
    return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<?> missing(NoSuchElementException e) {
    return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class
  })
  ResponseEntity<?> bad(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "message",
                e instanceof IllegalArgumentException ? e.getMessage() : "输入不完整或格式不正确，请检查后重试"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> error(Exception e) {
    return ResponseEntity.internalServerError().body(Map.of("message", "请求未完成，请检查服务配置后重试"));
  }
}
