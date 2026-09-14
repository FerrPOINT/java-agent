-- V59 (WP-6 / docs/35): durable OpenAI Runs state machine and event log.
--
-- openai_run_states: one row per run with the authoritative state machine
-- (queued/in_progress/requires_action/completed/failed/cancelled/expired),
-- session/user/profile binding, cancellation metadata and retention expiry.
-- openai_run_events: append-only monotonic event sequence per run for
-- restart-safe SSE replay (cursor = seq).

CREATE TABLE IF NOT EXISTS openai_run_states (
    run_id            VARCHAR(64) PRIMARY KEY,
    session_id        UUID NOT NULL,
    user_id           VARCHAR(128),
    profile           VARCHAR(64) NOT NULL DEFAULT 'default',
    external_run_id   VARCHAR(128),
    model             VARCHAR(128),
    state             VARCHAR(20) NOT NULL,
    state_reason      TEXT,
    cancel_reason     TEXT,
    cancelled_at      TIMESTAMPTZ,
    last_seq          BIGINT NOT NULL DEFAULT 0,
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_openai_run_state CHECK (
        state IN ('queued', 'in_progress', 'requires_action', 'completed',
                  'failed', 'cancelled', 'expired'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_openai_run_external
    ON openai_run_states (user_id, external_run_id)
    WHERE external_run_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_openai_run_state_updated
    ON openai_run_states (state, updated_at);

CREATE INDEX IF NOT EXISTS idx_openai_run_session
    ON openai_run_states (session_id);

CREATE TABLE IF NOT EXISTS openai_run_events (
    run_id       VARCHAR(64) NOT NULL,
    seq          BIGINT NOT NULL,
    event_json   TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);
