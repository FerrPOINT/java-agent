-- V29: Multi-user — add userId to CronJob, Skill, Checkpoint, CronExecutionLog.
-- These entities were previously global (single-user). Multi-user requires
-- per-user scoping so users only see/manage their own resources.
-- userId is nullable initially — existing rows get NULL and are treated as
-- "global" (visible to all users) until claimed.

ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS user_id TEXT;
ALTER TABLE skills ADD COLUMN IF NOT EXISTS user_id TEXT;
ALTER TABLE checkpoints ADD COLUMN IF NOT EXISTS user_id TEXT;
ALTER TABLE cron_execution_log ADD COLUMN IF NOT EXISTS user_id TEXT;

-- Indexes for per-user filtering
CREATE INDEX IF NOT EXISTS idx_cron_jobs_user_id ON cron_jobs(user_id);
CREATE INDEX IF NOT EXISTS idx_skills_user_id ON skills(user_id);
CREATE INDEX IF NOT EXISTS idx_checkpoints_user_id ON checkpoints(user_id);
CREATE INDEX IF NOT EXISTS idx_cron_execution_log_user_id ON cron_execution_log(user_id);