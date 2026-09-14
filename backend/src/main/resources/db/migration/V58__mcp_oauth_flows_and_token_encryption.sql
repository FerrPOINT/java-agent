-- V58 (WP-3 / docs/35): MCP OAuth Authorization Code + PKCE flow state and
-- token encryption.
--
-- mcp_oauth_flows: short-lived authorization flow state (state hash, PKCE
-- challenge, encrypted verifier, redirect URI, expiry, status transitions).
-- mcp_oauth_tokens: gains profile + server config foreign key, encryption key
-- version, and token scope; legacy name-only rows are migrated to the default
-- profile (server config link stays nullable — name-matched at runtime).

CREATE TABLE IF NOT EXISTS mcp_oauth_flows (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile            VARCHAR(64) NOT NULL DEFAULT 'default',
    server_name        VARCHAR(128) NOT NULL,
    state_hash         VARCHAR(64) NOT NULL,
    code_challenge     VARCHAR(128) NOT NULL,
    challenge_method   VARCHAR(16) NOT NULL DEFAULT 'S256',
    verifier_encrypted TEXT NOT NULL,
    redirect_uri       TEXT NOT NULL,
    authorization_url  TEXT NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    status             VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_mcp_oauth_flows_state UNIQUE (state_hash),
    CONSTRAINT chk_mcp_oauth_flow_status CHECK (status IN ('pending', 'completed', 'failed', 'cancelled', 'expired'))
);

CREATE INDEX IF NOT EXISTS idx_mcp_oauth_flows_server
    ON mcp_oauth_flows (profile, server_name, created_at DESC);

ALTER TABLE mcp_oauth_tokens
    ADD COLUMN IF NOT EXISTS profile VARCHAR(64) NOT NULL DEFAULT 'default',
    ADD COLUMN IF NOT EXISTS server_config_id UUID REFERENCES mcp_server_configs(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS encryption_key_version INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS token_scope TEXT;

DROP INDEX IF EXISTS idx_mcp_oauth_server_name;
CREATE INDEX IF NOT EXISTS idx_mcp_oauth_tokens_profile
    ON mcp_oauth_tokens (profile, server_name);
