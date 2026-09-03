-- Hermes-style claim/ack state for async delegate completion delivery.
ALTER TABLE delegated_task_runs
    ADD COLUMN IF NOT EXISTS delivery_claim VARCHAR(160),
    ADD COLUMN IF NOT EXISTS delivery_claimed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delivery_dropped_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_delegated_task_runs_delivery_claim
    ON delegated_task_runs (delivery_claim, delivery_claimed_at);

CREATE INDEX IF NOT EXISTS idx_delegated_task_runs_restorable_delivery
    ON delegated_task_runs (completed_at ASC)
    WHERE delivered_at IS NULL AND delivery_dropped_at IS NULL;
