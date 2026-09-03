-- V38: Hermes cron monitor/continuity/session-attach parity.
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS monitor TEXT;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS monitor_last_hash TEXT;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS monitor_last_output TEXT;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS monitor_last_changed_at TIMESTAMPTZ;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS continuity_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS attached_session_id UUID;
