-- Hermes-compatible session visibility flags.
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS pinned BOOLEAN DEFAULT FALSE;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS hidden BOOLEAN DEFAULT FALSE;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS unread BOOLEAN DEFAULT FALSE;

UPDATE sessions SET pinned = FALSE WHERE pinned IS NULL;
UPDATE sessions SET archived = FALSE WHERE archived IS NULL;
UPDATE sessions SET hidden = FALSE WHERE hidden IS NULL;
UPDATE sessions SET unread = FALSE WHERE unread IS NULL;

CREATE INDEX IF NOT EXISTS idx_sessions_user_visibility ON sessions(user_id, archived, hidden);
