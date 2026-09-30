CREATE TABLE run_event_outbox (
    id VARCHAR(80) PRIMARY KEY,
    run_id VARCHAR(80) NOT NULL REFERENCES ai_runs(id),
    event_type VARCHAR(40) NOT NULL,
    payload TEXT NOT NULL,
    terminal_payload TEXT NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    published_at VARCHAR(40)
);
CREATE INDEX idx_run_outbox_pending ON run_event_outbox(published_at, created_at);
