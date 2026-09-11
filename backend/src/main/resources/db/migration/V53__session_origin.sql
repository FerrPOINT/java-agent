-- V53: Session origin — where the conversation came from (platform/chat/thread).
-- The delivery ledger needs a durable answer to "where do I send the result of
-- this session's background work?" Cron and delegated runs inherit the origin
-- from their parent session at enqueue time; without these columns the backend
-- cannot name a platform target for delegate delivery (parity gap vs Hermes
-- delivery_ledger/gateway origin resolution).

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS origin_platform VARCHAR(64);
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS origin_chat_id VARCHAR(255);
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS origin_thread_id VARCHAR(255);
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS origin_user_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_sessions_origin_chat
    ON sessions (origin_platform, origin_chat_id);
