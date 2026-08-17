-- V27: Cron execution ledger (h72) and Curator audit ledger (h77).
-- Adds cron_execution_log and skill_audit_log tables.

-- h72: Cron execution ledger — records each cron job execution.
CREATE TABLE IF NOT EXISTS cron_execution_log (
    id BIGSERIAL PRIMARY KEY,
    job_id UUID NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_cron_execution_log_job_id ON cron_execution_log(job_id);
CREATE INDEX IF NOT EXISTS idx_cron_execution_log_started_at ON cron_execution_log(started_at);

-- h71/h74: Add status tracking columns to cron_jobs for persisted-state recovery
-- and retry storm suppression.
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS last_status VARCHAR(50);
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS last_error TEXT;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS last_error_at TIMESTAMP;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS consecutive_failures INTEGER NOT NULL DEFAULT 0;

-- h77: Curator audit ledger — records each skill mutation.
CREATE TABLE IF NOT EXISTS skill_audit_log (
    id BIGSERIAL PRIMARY KEY,
    skill_name VARCHAR(500) NOT NULL,
    action VARCHAR(50) NOT NULL,
    user_id VARCHAR(255),
    old_value TEXT,
    new_value TEXT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_skill_audit_log_skill_name ON skill_audit_log(skill_name);
CREATE INDEX IF NOT EXISTS idx_skill_audit_log_timestamp ON skill_audit_log(timestamp);