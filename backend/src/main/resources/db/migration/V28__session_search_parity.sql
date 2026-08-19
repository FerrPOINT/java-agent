-- V28: Session search parity with Hermes — add fields needed for 4-mode session_search.
-- sessions.source: identifies the session origin (telegram, cli, cron, subagent, kanban, etc.)
-- sessions.end_reason: how the session ended (compression, new_session, idle_timeout, daily_reset, branched)
-- sessions.preview: short preview text for browse mode
-- sessions.last_active: last activity timestamp (distinct from updated_at which tracks row writes)
-- sessions.message_count: cached count for browse mode efficiency
-- messages.active: whether the message is in live context (false after compaction archive)
-- messages.compacted: whether the message was archived by compaction (active=false, compacted=true)

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS source TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS end_reason TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS preview TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS last_active TIMESTAMPTZ;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS message_count INTEGER DEFAULT 0;

ALTER TABLE messages ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT true;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS compacted BOOLEAN DEFAULT false;

-- Index for browse mode: recent sessions excluding hidden sources, ordered by last_active
CREATE INDEX IF NOT EXISTS idx_sessions_source ON sessions(source);

-- Index for message active filtering

-- Backfill: set source='telegram' for existing sessions that have no source
-- (existing sessions were created before the source field existed)
UPDATE sessions SET source = 'telegram' WHERE source IS NULL;
UPDATE sessions SET last_active = updated_at WHERE last_active IS NULL;
UPDATE sessions SET message_count = (SELECT count(*) FROM messages m WHERE m.session_id = sessions.id) WHERE message_count = 0;