CREATE TABLE ai_runs (
 id VARCHAR(40) PRIMARY KEY, tenant_id VARCHAR(40) NOT NULL, owner_id VARCHAR(40) NOT NULL,
 session_id VARCHAR(80) NOT NULL, request_id VARCHAR(80) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 message TEXT NOT NULL, status VARCHAR(20) NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
 next_seq BIGINT NOT NULL DEFAULT 0, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL, error TEXT,
 UNIQUE(tenant_id,owner_id,session_id,request_id), FOREIGN KEY(tenant_id) REFERENCES tenants(id)
);
CREATE UNIQUE INDEX idx_ai_one_active_user ON ai_runs(tenant_id,owner_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX idx_ai_session ON ai_runs(tenant_id,owner_id,session_id,created_at);
CREATE TABLE ai_run_events (
 run_id VARCHAR(40) NOT NULL, seq BIGINT NOT NULL, name VARCHAR(40) NOT NULL, payload TEXT NOT NULL,
 PRIMARY KEY(run_id,seq), FOREIGN KEY(run_id) REFERENCES ai_runs(id)
);
CREATE TABLE ai_usage (
 id VARCHAR(40) PRIMARY KEY, tenant_id VARCHAR(40) NOT NULL, owner_id VARCHAR(40) NOT NULL,
 run_id VARCHAR(40), purpose VARCHAR(40) NOT NULL, created_at VARCHAR(40) NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX idx_ai_usage_owner ON ai_usage(tenant_id,owner_id,created_at);
CREATE INDEX idx_ai_usage_run ON ai_usage(tenant_id,owner_id,run_id);
