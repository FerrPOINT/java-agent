-- Retain cleanup intent when an old session is deactivated.
ALTER TABLE bot_sessions ADD COLUMN IF NOT EXISTS cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE bot_sessions ADD COLUMN IF NOT EXISTS cleanup_on_backend_binding BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_bot_sessions_cleanup_pending ON bot_sessions (id) WHERE cleanup_pending = TRUE;
