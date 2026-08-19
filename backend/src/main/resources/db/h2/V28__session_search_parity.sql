-- H2 variant of V28: H2 has no tsvector-free partial indexes support
-- (WHERE clause in CREATE INDEX) and no tsvector/GIN.
-- Plain variants of the partial indexes; FTS columns omitted —
-- SessionSearchService falls back to LIKE-based search on H2.
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS source TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS end_reason TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS preview TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS last_active TIMESTAMPTZ;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS message_count INTEGER DEFAULT 0;

ALTER TABLE messages ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT true;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS compacted BOOLEAN DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_sessions_source ON sessions(source);
CREATE INDEX IF NOT EXISTS idx_sessions_last_active ON sessions(last_active DESC);
CREATE INDEX IF NOT EXISTS idx_messages_session_active ON messages(session_id);

UPDATE sessions SET source = 'telegram' WHERE source IS NULL;
UPDATE sessions SET last_active = updated_at WHERE last_active IS NULL;
UPDATE sessions SET message_count = (SELECT count(*) FROM messages m WHERE m.session_id = sessions.id) WHERE message_count = 0;
