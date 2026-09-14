-- V60 (WP-7 / docs/35): execute-code session kernel metadata.
--
-- Persists kernel IDENTITY and lease only — never a live process handle.
-- After a server restart the rows are marked lost by the manager and callers
-- get `kernel_lost`, recreating the kernel deliberately.

CREATE TABLE IF NOT EXISTS code_session_kernels (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kernel_id         VARCHAR(64) NOT NULL,
    session_id        UUID NOT NULL,
    profile           VARCHAR(64) NOT NULL DEFAULT 'default',
    user_id           VARCHAR(128),
    state             VARCHAR(16) NOT NULL DEFAULT 'alive',
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_code_kernel_session UNIQUE (session_id),
    CONSTRAINT chk_code_kernel_state CHECK (state IN ('alive', 'lost', 'expired', 'killed'))
);

CREATE INDEX IF NOT EXISTS idx_code_kernel_expiry
    ON code_session_kernels (state, expires_at);
