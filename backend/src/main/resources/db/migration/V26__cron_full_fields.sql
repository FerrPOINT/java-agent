-- V26: Full cron job field parity with Hermes (repeat, one-shot, no_agent, overrides).
-- Adds: repeat_count, repeat_completed, script, no_agent, enabled_toolsets,
-- workdir, model_provider, model_name, base_url columns.
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS repeat_count INTEGER;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS repeat_completed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS script TEXT;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS no_agent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS enabled_toolsets TEXT;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS workdir TEXT;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS model_provider TEXT;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS model_name TEXT;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS base_url TEXT;