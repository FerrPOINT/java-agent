-- S17: Cron per-job skill loading — attach skills to cron jobs
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS skills TEXT;