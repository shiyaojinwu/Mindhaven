package com.mindhaven.service.chat;

import com.mindhaven.manager.MessageManager;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.manager.SessionManager;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.Metrics;
import com.mindhaven.model.chat.Session;
import com.mindhaven.model.chat.Summary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static com.mindhaven.common.Times.now;

@Service
public class SessionService {
    private final SessionManager sessions;
    private final MessageManager messages;
    private final RecordManager store;

    public SessionService(SessionManager sessions, MessageManager messages, RecordManager store) {
        this.sessions = sessions;
        this.messages = messages;
        this.store = store;
    }

    public Session create() {
        var s = new Session(UUID.randomUUID().toString(), "新的对话", now());
        sessions.save(s);
        return s;
    }

    public List<Session> sessions() {
        return sessions.list().reversed();
    }

    public Session session(String id) {
        return sessions.get(id).orElseThrow(() -> new NoSuchElementException("对话不存在"));
    }

    public List<ChatMessage> history(String id) {
        session(id);
        return messages.list(id);
    }

    public Optional<Summary> summary(String id) {
        session(id);
        return store.get("summaries", id, Summary.class);
    }

    public List<Metrics> metrics() {
        return store.list("metrics", Metrics.class).reversed();
    }

}
