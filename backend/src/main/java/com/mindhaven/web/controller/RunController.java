package com.mindhaven.web.controller;

import com.mindhaven.application.ai.*;
import com.mindhaven.application.dto.ChatCommand;
import com.mindhaven.web.stream.RunEventStream;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class RunController {
  private final ChatRunService runs;
  private final RunEventStream streams;
  private final AiOperations ai;

  public RunController(ChatRunService runs, RunEventStream streams, AiOperations ai) {
    this.runs = runs;
    this.streams = streams;
    this.ai = ai;
  }

  @PostMapping("/sessions/{id}/runs")
  public Object create(@PathVariable String id, @Valid @RequestBody ChatCommand input) {
    return runs.create(id, input);
  }

  @GetMapping("/sessions/{id}/runs")
  public Object recent(@PathVariable String id) {
    return runs.recent(id);
  }

  @GetMapping("/runs/{id}")
  public Object get(@PathVariable String id) {
    return runs.get(id);
  }

  @PostMapping("/runs/{id}/cancel")
  public Object cancel(@PathVariable String id) {
    return runs.cancel(id);
  }

  @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
    return streams.open(id, after);
  }

  @GetMapping("/usage")
  public Object usage(@RequestParam(required = false) String runId) {
    if (runId != null && !runId.startsWith("report:")) runs.get(runId);
    return ai.usage(runId);
  }
}
