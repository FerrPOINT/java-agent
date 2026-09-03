ALTER TABLE cron_jobs
    ADD COLUMN IF NOT EXISTS provider_snapshot VARCHAR(255),
    ADD COLUMN IF NOT EXISTS model_snapshot VARCHAR(255);
