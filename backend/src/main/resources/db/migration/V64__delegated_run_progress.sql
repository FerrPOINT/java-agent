-- Stalled-run monitor: progress tracking for delegated task runs.
ALTER TABLE delegated_task_runs
    ADD COLUMN IF NOT EXISTS last_progress_at TIMESTAMPTZ;

ALTER TABLE delegated_task_runs
    ADD COLUMN IF NOT EXISTS last_progress_summary TEXT;

ALTER TABLE delegated_task_runs
    ADD COLUMN IF NOT EXISTS stalled_diagnostic_at TIMESTAMPTZ;

-- Running runs without progress fall back to started_at/created_at for staleness.
CREATE INDEX IF NOT EXISTS idx_delegated_task_runs_stalled_scan
    ON delegated_task_runs (status, last_progress_at)
    WHERE status = 'running';
