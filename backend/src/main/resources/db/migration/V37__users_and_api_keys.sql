-- V37: Multi-user authentication — user accounts and per-user API keys.
-- API keys are stored only as SHA-256 hashes. The raw value is returned once
-- at provisioning time and is never persisted.

CREATE TABLE IF NOT EXISTS agent_users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    display_name TEXT,
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (role IN ('admin', 'user'))
);

CREATE TABLE IF NOT EXISTS user_api_keys (
    id UUID PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES agent_users(id) ON DELETE CASCADE,
    key_hash CHAR(64) NOT NULL UNIQUE,
    label TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_user_api_keys_user_id ON user_api_keys(user_id);