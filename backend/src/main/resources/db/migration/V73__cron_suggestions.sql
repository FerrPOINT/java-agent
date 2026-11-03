-- Durable, owner-scoped cron suggestions survive backend restarts and isolate decisions.
CREATE TABLE cron_suggestions (
    id UUID PRIMARY KEY,
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    source VARCHAR(64) NOT NULL,
    job_name VARCHAR(255) NOT NULL,
    job_schedule VARCHAR(255) NOT NULL,
    job_prompt TEXT NOT NULL,
    deliver_to VARCHAR(255),
    skills VARCHAR(2048),
    dedup_key VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT uk_cron_suggestions_user_dedup UNIQUE (user_id, dedup_key),
    CONSTRAINT chk_cron_suggestions_status CHECK (status IN ('pending', 'accepted', 'dismissed'))
);

CREATE INDEX idx_cron_suggestions_user_status_created
    ON cron_suggestions (user_id, status, created_at, id);
