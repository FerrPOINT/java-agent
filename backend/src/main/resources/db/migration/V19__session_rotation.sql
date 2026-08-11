-- V19: Add session rotation support for compression.
-- parent_session_id links a child session to its predecessor after compression-based rotation.
-- session_status tracks whether a session is 'active' (default) or 'compressed' (superseded by a child).

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS parent_session_id UUID;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS session_status VARCHAR(32) DEFAULT 'active';

CREATE INDEX IF NOT EXISTS idx_sessions_parent_session_id ON sessions(parent_session_id);
CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(session_status);