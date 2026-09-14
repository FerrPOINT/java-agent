-- V56 (WP-4 / ADR-013): profile runtime registry, config revision log and
-- dashboard action ledger.
--
-- profile_runtime_state is the authoritative per-profile runtime view that
-- replaces static dashboard JSON: config/tool-registry/skill revisions, gateway
-- binding, worker state and last reload outcome.
-- profile_config_revisions is an append-only audit log of profile config writes
-- (summary hash only, never raw secret content).
-- dashboard_actions tracks whitelisted dashboard operations (doctor, dump,
-- security audit, backup, ...) with state/output-path/actor for recovery.

CREATE TABLE IF NOT EXISTS profile_runtime_state (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile              VARCHAR(64) NOT NULL,
    config_revision      BIGINT NOT NULL DEFAULT 0,
    tool_registry_revision BIGINT NOT NULL DEFAULT 0,
    skill_revision       BIGINT NOT NULL DEFAULT 0,
    gateway_state        VARCHAR(32) NOT NULL DEFAULT 'unbound',
    worker_state         VARCHAR(32) NOT NULL DEFAULT 'stopped',
    last_reload_status   VARCHAR(32),
    last_reload_error    TEXT,
    last_reload_at       TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_profile_runtime_state_profile UNIQUE (profile),
    CONSTRAINT chk_profile_runtime_worker CHECK (worker_state IN
        ('running', 'draining', 'stopped', 'failed'))
);

CREATE TABLE IF NOT EXISTS profile_config_revisions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile        VARCHAR(64) NOT NULL,
    revision       BIGINT NOT NULL,
    actor          VARCHAR(128),
    summary_hash   VARCHAR(128) NOT NULL,
    status         VARCHAR(32) NOT NULL DEFAULT 'applied',
    detail         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_profile_config_revision UNIQUE (profile, revision),
    CONSTRAINT chk_profile_config_revision_status CHECK (status IN
        ('applied', 'failed', 'rolled_back'))
);

CREATE INDEX IF NOT EXISTS idx_profile_config_revisions_profile
    ON profile_config_revisions (profile, created_at DESC);

CREATE TABLE IF NOT EXISTS dashboard_actions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action         VARCHAR(64) NOT NULL,
    profile        VARCHAR(64),
    state          VARCHAR(32) NOT NULL DEFAULT 'pending',
    output_path    TEXT,
    output_sha256  VARCHAR(64),
    actor          VARCHAR(128),
    detail         TEXT,
    requested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    CONSTRAINT chk_dashboard_action_state CHECK (state IN
        ('pending', 'running', 'completed', 'failed', 'cancelled'))
);

CREATE INDEX IF NOT EXISTS idx_dashboard_actions_recent
    ON dashboard_actions (requested_at DESC);
