-- Legacy single-user records stay untouched and are never exposed to new tenants.
CREATE TABLE IF NOT EXISTS records (bucket VARCHAR(80) NOT NULL,id VARCHAR(80) NOT NULL,payload TEXT NOT NULL,created_at VARCHAR(40) NOT NULL,PRIMARY KEY(bucket,id));
CREATE TABLE IF NOT EXISTS tenants (id VARCHAR(40) PRIMARY KEY,slug VARCHAR(40) NOT NULL UNIQUE,name VARCHAR(120) NOT NULL,created_at VARCHAR(40) NOT NULL);
CREATE TABLE IF NOT EXISTS tenant_users (id VARCHAR(40) PRIMARY KEY,tenant_id VARCHAR(40) NOT NULL,username VARCHAR(60) NOT NULL,password_hash VARCHAR(250) NOT NULL,role VARCHAR(20) NOT NULL,created_at VARCHAR(40) NOT NULL,UNIQUE(tenant_id,username),FOREIGN KEY(tenant_id) REFERENCES tenants(id));
CREATE TABLE IF NOT EXISTS auth_sessions (token_hash VARCHAR(64) PRIMARY KEY,user_id VARCHAR(40) NOT NULL,expires_at BIGINT NOT NULL,FOREIGN KEY(user_id) REFERENCES tenant_users(id));
CREATE INDEX IF NOT EXISTS idx_auth_expiry ON auth_sessions(expires_at);
CREATE TABLE IF NOT EXISTS tenant_records (tenant_id VARCHAR(40) NOT NULL,owner_id VARCHAR(40) NOT NULL,bucket VARCHAR(100) NOT NULL,id VARCHAR(100) NOT NULL,payload TEXT NOT NULL,created_at VARCHAR(40) NOT NULL,PRIMARY KEY(tenant_id,owner_id,bucket,id),FOREIGN KEY(tenant_id) REFERENCES tenants(id));
CREATE INDEX IF NOT EXISTS idx_tenant_records_list ON tenant_records(tenant_id,owner_id,bucket,created_at);
