ALTER TABLE cron_jobs
    ADD COLUMN configuration_revision BIGINT NOT NULL DEFAULT 0;
