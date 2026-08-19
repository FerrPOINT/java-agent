-- H2: plain (non-partial) variants of the V28 PostgreSQL partial indexes.
-- H2 does not support partial indexes; these provide the same base indexes.
CREATE INDEX IF NOT EXISTS idx_sessions_last_active ON sessions(last_active DESC);
CREATE INDEX IF NOT EXISTS idx_messages_session_active ON messages(session_id);
