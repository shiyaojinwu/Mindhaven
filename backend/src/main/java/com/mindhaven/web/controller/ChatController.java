package com.mindhaven.web.controller;

import com.mindhaven.application.chat.ChatService;
import com.mindhaven.domain.model.Models.*;
import com.mindhaven.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {
  private final ChatService chat;
  private final ExecutorService workers =
      new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));

  public ChatController(ChatService chat) {
    this.chat = chat;
  }

  @jakarta.annotation.PreDestroy
  void stop() {
    workers.shutdownNow();
  }

  @GetMapping("/sessions")
  public Object sessions() {
    return chat.sessions();
  }

  @PostMapping("/sessions")
  public Object create() {
    return chat.create();
  }

  @GetMapping("/sessions/{id}/messages")
  public Object messages(@PathVariable String id) {
    return chat.history(id);
  }

  @GetMapping("/sessions/{id}/summary")
  public Object summary(@PathVariable String id) {
    return chat.summary(id).<Object>map(s -> s).orElse(Map.of());
  }

  public record ChatInput(
      @NotBlank @Size(max = 1500) String message,
      @NotBlank @Size(max = 40) String topic,
      @NotBlank @Size(max = 40) String version,
      boolean rewrite,
      boolean compression) {}

  @PostMapping(value = "/sessions/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable String id, @Valid @RequestBody ChatInput input) {
    var identity = TenantContext.require();
    chat.session(id);
    SseEmitter emitter = new SseEmitter(180000L);
    java.util.concurrent.Future<?>[] job = {null};
    emitter.onTimeout(
        () -> {
          if (job[0] != null) job[0].cancel(true);
          emitter.complete();
        });
    emitter.onError(
        e -> {
          if (job[0] != null) job[0].cancel(true);
        });
    try {
      job[0] =
          workers.submit(
              () -> {
                try (var scope = TenantContext.open(identity)) {
                  chat.turn(
                      id,
                      input.message(),
                      input.topic(),
                      input.version(),
                      input.rewrite(),
                      input.compression(),
                      (name, data) -> {
                        try {
                          emitter.send(SseEmitter.event().name(name).data(data));
                        } catch (IOException e) {
                          throw new IllegalStateException("Client disconnected");
                        }
                      });
                  emitter.complete();
                } catch (Exception e) {
                  try {
                    emitter.send(
                        SseEmitter.event()
                            .name("error")
                            .data(
                                Map.of(
                                    "message",
                                    e instanceof IllegalArgumentException
                                        ? e.getMessage()
                                        : "生成失败，请检查模型、Embedding 和 Qdrant 配置后重试。")));
                    emitter.complete();
                  } catch (IOException ex) {
                    emitter.completeWithError(ex);
                  }
                }
              });
    } catch (RejectedExecutionException e) {
      throw new IllegalArgumentException("当前请求较多，请稍后重试");
    }
    return emitter;
  }

  @GetMapping("/metrics")
  public Object metrics() {
    return chat.metrics();
  }
}
