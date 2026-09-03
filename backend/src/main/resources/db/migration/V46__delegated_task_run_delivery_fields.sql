-- Delivery ledger for Hermes-style async delegate completion replay.
ALTER TABLE delegated_task_runs
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delivery_target VARCHAR(128),
    ADD COLUMN IF NOT EXISTS delivery_error TEXT,
    ADD COLUMN IF NOT EXISTS delivery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS delivery_idempotency_key VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_delegated_task_runs_pending_delivery
    ON delegated_task_runs (parent_session_id, delivered_at, completed_at ASC);
