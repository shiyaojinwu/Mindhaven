CREATE TABLE knowledge_index_state (
 tenant_id VARCHAR(40) NOT NULL,
 target VARCHAR(64) NOT NULL,
 chunk_id VARCHAR(80) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 status VARCHAR(20) NOT NULL,
 updated_at VARCHAR(40) NOT NULL,
 PRIMARY KEY (tenant_id, target, chunk_id),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);
