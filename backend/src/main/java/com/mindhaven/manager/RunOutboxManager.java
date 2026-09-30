package com.mindhaven.manager;

import com.mindhaven.mapper.RunOutboxMapper;
import com.mindhaven.model.chat.RunNotification;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

@Component
public class RunOutboxManager {
    private final RunOutboxMapper mapper;
    public RunOutboxManager(RunOutboxMapper mapper) { this.mapper = mapper; }
    public List<RunNotification> pending() { return mapper.pending(); }
    public void delivered(String id) { mapper.delivered(id, Instant.now().toString()); }
}
