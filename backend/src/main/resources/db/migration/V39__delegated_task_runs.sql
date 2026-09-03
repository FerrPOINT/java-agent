-- Durable async delegate_task ledger (Hermes async_delegations parity subset).
CREATE TABLE IF NOT EXISTS delegated_task_runs (
    id                  UUID PRIMARY KEY,
    parent_session_id   UUID NOT NULL,
    child_session_id    UUID,
    profile             VARCHAR(128),
    goal                TEXT NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'running',
    result_json         TEXT,
    error               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_delegated_task_runs_parent_created
    ON delegated_task_runs (parent_session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_delegated_task_runs_status
    ON delegated_task_runs (status);
