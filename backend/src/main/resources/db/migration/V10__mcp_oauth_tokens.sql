-- V10: Create mcp_oauth_tokens table for MCP OAuth token storage
CREATE TABLE mcp_oauth_tokens (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    server_name VARCHAR NOT NULL UNIQUE,
    access_token VARCHAR NOT NULL,
    refresh_token VARCHAR,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mcp_oauth_server_name ON mcp_oauth_tokens(server_name);