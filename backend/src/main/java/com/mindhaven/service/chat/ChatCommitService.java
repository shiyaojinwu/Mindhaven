package com.mindhaven.service.chat;

import com.mindhaven.manager.MessageManager;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.manager.SessionManager;
import com.mindhaven.model.chat.ChatMessage;
import com.mindhaven.model.chat.Metrics;
import com.mindhaven.model.chat.Session;
import com.mindhaven.model.chat.TurnResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

@Service
public class ChatCommitService {
    private final MessageManager messages;
    private final SessionManager sessions;
    private final RecordManager records;

    public ChatCommitService(MessageManager messages, SessionManager sessions, RecordManager records) {
        this.messages = messages;
        this.sessions = sessions;
        this.records = records;
    }

    /** The runtime callback joins this transaction; any failure rolls back the entire completed turn. */
    @Transactional
    public void commit(Session session, ChatMessage user, ChatMessage assistant, Metrics metrics,
                       Consumer<TurnResult> onCommit) {
        messages.save(user);
        messages.save(assistant);
        records.put("metrics", metrics.id(), metrics);
        if (session.title().equals("新的对话")) {
            String title = user.content().substring(0, Math.min(user.content().length(), 18));
            sessions.save(new Session(session.id(), title, session.createdAt()));
        }
        onCommit.accept(new TurnResult(assistant, metrics));
    }
}
