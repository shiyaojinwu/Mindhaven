package com.mindhaven.web.controller;

import com.mindhaven.application.ai.*;
import com.mindhaven.application.chat.ChatService;
import com.mindhaven.application.dto.ChatCommand;
import com.mindhaven.web.stream.RunEventStream;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {
  private final ChatService chat;
  private final ChatRunService runs;
  private final RunEventStream streams;

  public ChatController(ChatService chat, ChatRunService runs, RunEventStream streams) {
    this.chat = chat;
    this.runs = runs;
    this.streams = streams;
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

  @GetMapping("/metrics")
  public Object metrics() {
    return chat.metrics();
  }

  /** Compatibility endpoint for existing clients; new clients create runs explicitly. */
  @PostMapping(value = "/sessions/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable String id, @Valid @RequestBody ChatCommand input) {
    var command =
        new ChatCommand(
            input.message(),
            input.topic(),
            input.version(),
            input.rewrite(),
            input.compression(),
            input.requestId() == null ? UUID.randomUUID().toString() : input.requestId());
    return streams.open(runs.create(id, command).id(), 0);
  }
}
