-- V61 (WP-9 / ADR-015): durable console command tasks with cursor replay.
--
-- console_tasks: guarded dashboard command tasks with ownership and a
-- terminal state machine. console_task_output: redacted output lines with a
-- monotonic per-task sequence — reconnect replays strictly after the client
-- cursor (no duplicates, no gaps).

CREATE TABLE IF NOT EXISTS console_tasks (
    id              VARCHAR(64) PRIMARY KEY,
    profile         VARCHAR(64) NOT NULL DEFAULT 'default',
    user_id         VARCHAR(128),
    session_id      UUID,
    command         TEXT NOT NULL,
    workdir         TEXT,
    state           VARCHAR(16) NOT NULL DEFAULT 'running',
    exit_code       INT,
    timeout_seconds INT NOT NULL DEFAULT 60,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours',
    CONSTRAINT chk_console_task_state CHECK (
        state IN ('running', 'completed', 'failed', 'cancelled', 'timeout'))
);

CREATE INDEX IF NOT EXISTS idx_console_tasks_expiry
    ON console_tasks (state, expires_at);

CREATE INDEX IF NOT EXISTS idx_console_tasks_owner
    ON console_tasks (profile, user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS console_task_output (
    task_id     VARCHAR(64) NOT NULL REFERENCES console_tasks(id) ON DELETE CASCADE,
    sequence    BIGINT NOT NULL,
    line        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, sequence)
);

CREATE INDEX IF NOT EXISTS idx_console_output_task
    ON console_task_output (task_id, sequence DESC);
