-- V7: Create cron_jobs table for scheduled agent tasks
CREATE TABLE cron_jobs (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    schedule VARCHAR(255) NOT NULL,
    prompt TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deliver_to VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_run_at TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ
);

CREATE INDEX idx_cron_jobs_enabled ON cron_jobs(enabled);