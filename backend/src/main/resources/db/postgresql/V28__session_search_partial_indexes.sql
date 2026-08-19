-- PostgreSQL-only partial indexes for V28 (H2 has no partial index support).
CREATE INDEX IF NOT EXISTS idx_sessions_last_active ON sessions(last_active DESC) WHERE source IS NULL OR source NOT IN ('kanban', 'subagent', 'tool');
CREATE INDEX IF NOT EXISTS idx_messages_session_active ON messages(session_id) WHERE active = true;
