-- Background jobs (Hermes parity: run_in_background job model with status + result)
CREATE TABLE IF NOT EXISTS background_jobs (
    id           UUID PRIMARY KEY,
    session_id   UUID,
    prompt       VARCHAR(4000),
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    result       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_background_jobs_session ON background_jobs (session_id);
