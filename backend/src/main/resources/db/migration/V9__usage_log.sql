-- V9: Create usage_log table for tracking token usage per turn/session/day
CREATE TABLE usage_log (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id UUID,
    user_id VARCHAR,
    model VARCHAR,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    cost DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_log_session_id ON usage_log(session_id);
CREATE INDEX idx_usage_log_user_id ON usage_log(user_id);
CREATE INDEX idx_usage_log_created_at ON usage_log(created_at);