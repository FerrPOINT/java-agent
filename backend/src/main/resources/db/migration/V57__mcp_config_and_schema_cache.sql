-- V57 (WP-3 / docs/35): persisted MCP server configs and durable schema cache.
--
-- mcp_server_configs: profile-scoped MCP server configuration with a monotonic
-- config revision. Non-secret fields only: transport, endpoint/command, args,
-- env KEY REFERENCES (never values), include/exclude, timeout, trust, OAuth
-- metadata (urls/client id — never secrets).
-- mcp_schema_cache: last known-good tools/resources/prompts schemas per server
-- config revision with content hash and fetched/expiry/error metadata. Schemas
-- only — never secrets or raw credentials.

CREATE TABLE IF NOT EXISTS mcp_server_configs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile           VARCHAR(64) NOT NULL,
    name              VARCHAR(128) NOT NULL,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    transport         VARCHAR(16) NOT NULL DEFAULT 'stdio',
    command           TEXT,
    args              TEXT,
    base_url          TEXT,
    env_keys          TEXT,
    headers           TEXT,
    include_tools     TEXT,
    exclude_tools     TEXT,
    timeout_seconds   DOUBLE PRECISION NOT NULL DEFAULT 0,
    trust             VARCHAR(16) NOT NULL DEFAULT 'full',
    oauth_token_url   TEXT,
    oauth_client_id   TEXT,
    oauth_scopes      TEXT,
    config_revision   BIGINT NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_mcp_server_config_profile_name UNIQUE (profile, name),
    CONSTRAINT chk_mcp_transport CHECK (transport IN ('stdio', 'http', 'sse'))
);

CREATE INDEX IF NOT EXISTS idx_mcp_server_configs_profile
    ON mcp_server_configs (profile, name);

CREATE TABLE IF NOT EXISTS mcp_schema_cache (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    server_config_id  UUID NOT NULL REFERENCES mcp_server_configs(id) ON DELETE CASCADE,
    config_revision   BIGINT NOT NULL,
    content_hash      VARCHAR(64) NOT NULL,
    tools_json        TEXT,
    resources_json    TEXT,
    prompts_json      TEXT,
    fetched_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ,
    last_success_at   TIMESTAMPTZ,
    last_error        TEXT,
    CONSTRAINT uk_mcp_schema_cache_revision UNIQUE (server_config_id, config_revision)
);

CREATE INDEX IF NOT EXISTS idx_mcp_schema_cache_server
    ON mcp_schema_cache (server_config_id, fetched_at DESC);
