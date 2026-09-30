package com.mindhaven.controller;

import com.mindhaven.controller.stream.RunEventStream;
import com.mindhaven.model.ai.AiUsage;
import com.mindhaven.model.chat.AiRun;
import com.mindhaven.model.dto.ChatCommand;
import com.mindhaven.service.ai.*;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

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
    public AiRun create(@PathVariable String id, @Valid @RequestBody ChatCommand input) {
        return runs.create(id, input);
    }

    @GetMapping("/sessions/{id}/runs")
    public List<AiRun> recent(@PathVariable String id) {
        return runs.recent(id);
    }

    @PostMapping("/runs/{id}/continue")
    public AiRun continueRun(@PathVariable String id) {
        return runs.continueRun(id);
    }

    @GetMapping("/runs/{id}")
    public AiRun get(@PathVariable String id) {
        return runs.get(id);
    }

    @PostMapping("/runs/{id}/cancel")
    public AiRun cancel(@PathVariable String id) {
        return runs.cancel(id);
    }

    @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, @RequestParam(required = false) String after,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return streams.open(id, after == null ? lastEventId : after);
    }

    @GetMapping("/usage")
    public List<AiUsage> usage(@RequestParam(required = false) String runId) {
        if (runId != null && !runId.startsWith("report:")) runs.get(runId);
        return ai.usage(runId);
    }
}
