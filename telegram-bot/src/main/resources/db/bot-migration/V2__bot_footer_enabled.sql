-- Add footer_enabled column to bot_sessions
ALTER TABLE bot_sessions ADD COLUMN IF NOT EXISTS footer_enabled BOOLEAN NOT NULL DEFAULT FALSE;