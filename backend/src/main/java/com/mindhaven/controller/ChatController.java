package com.mindhaven.controller;

import com.mindhaven.controller.stream.RunEventStream;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.Metrics;
import com.mindhaven.model.chat.Session;
import com.mindhaven.model.dto.ChatCommand;
import com.mindhaven.model.vo.SummaryResponse;
import com.mindhaven.service.ai.*;
import com.mindhaven.service.chat.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final SessionService sessions;
    private final ChatRunService runs;
    private final RunEventStream streams;

    public ChatController(SessionService sessions, ChatRunService runs, RunEventStream streams) {
        this.sessions = sessions;
        this.runs = runs;
        this.streams = streams;
    }

    @GetMapping("/sessions")
    public List<Session> sessions() {
        return sessions.sessions();
    }

    @PostMapping("/sessions")
    public Session create() {
        return sessions.create();
    }

    @GetMapping("/sessions/{id}/messages")
    public List<ChatMessage> messages(@PathVariable String id) {
        return sessions.history(id);
    }

    @GetMapping("/sessions/{id}/summary")
    public SummaryResponse summary(@PathVariable String id) {
        return sessions.summary(id).map(SummaryResponse::from).orElseGet(SummaryResponse::empty);
    }

    @GetMapping("/metrics")
    public List<Metrics> metrics() {
        return sessions.metrics();
    }

    /**
     * Compatibility endpoint for existing clients; new clients create runs explicitly.
     */
    @PostMapping(value = "/sessions/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @Valid @RequestBody ChatCommand input) {
        var command = new ChatCommand(input.message(), input.topic(), input.version(), input.rewrite(), input.requestId() == null ? UUID.randomUUID().toString() : input.requestId());
        return streams.open(runs.create(id, command).id(), 0);
    }
}
