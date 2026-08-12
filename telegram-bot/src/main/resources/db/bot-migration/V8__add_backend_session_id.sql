-- V8: Add backend_session_id column to track the backend's session ID
-- The bot sends this UUID to the backend so it can find conversation history.
ALTER TABLE bot_sessions ADD COLUMN IF NOT EXISTS backend_session_id UUID;