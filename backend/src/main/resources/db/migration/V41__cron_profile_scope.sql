-- V41: Hermes profile ownership for cron jobs.
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS profile VARCHAR(128) NOT NULL DEFAULT 'default';

UPDATE cron_jobs
SET profile = 'default'
WHERE profile IS NULL OR TRIM(profile) = '';

CREATE INDEX IF NOT EXISTS idx_cron_jobs_profile_enabled_created
    ON cron_jobs(profile, enabled, created_at DESC, id ASC);

CREATE INDEX IF NOT EXISTS idx_cron_jobs_profile_name
    ON cron_jobs(profile, name);
