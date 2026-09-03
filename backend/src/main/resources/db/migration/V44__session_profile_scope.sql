-- V40: Hermes profile ownership for sessions.
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS profile VARCHAR(128) NOT NULL DEFAULT 'default';

UPDATE sessions
SET profile = 'default'
WHERE profile IS NULL OR TRIM(profile) = '';

CREATE INDEX IF NOT EXISTS idx_sessions_profile_user_recent
    ON sessions(profile, user_id, last_active DESC, updated_at DESC, id ASC);

CREATE INDEX IF NOT EXISTS idx_sessions_profile_user_created
    ON sessions(profile, user_id, created_at DESC, updated_at DESC, id ASC);
