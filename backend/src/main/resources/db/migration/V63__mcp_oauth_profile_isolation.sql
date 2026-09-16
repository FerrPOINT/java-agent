-- V63 (upstream 399238f2c2 parity): isolate MCP OAuth token connections by
-- profile. V58 added the profile column but left V10's UNIQUE(server_name)
-- constraint and the repository still resolved tokens by server name alone —
-- one profile's stored OAuth could authorize another profile's MCP requests.
--
-- Rebuild the uniqueness scope as (profile, server_name): drop the legacy
-- single-column unique constraint/index and enforce the composite one.

ALTER TABLE mcp_oauth_tokens
    DROP CONSTRAINT IF EXISTS mcp_oauth_tokens_server_name_key;

DROP INDEX IF EXISTS idx_mcp_oauth_server_name;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mcp_oauth_profile_server
    ON mcp_oauth_tokens (profile, server_name);
