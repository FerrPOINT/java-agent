-- V5: Add voice_mode column to bot_sessions for /voice command
ALTER TABLE bot_sessions ADD COLUMN IF NOT EXISTS voice_mode BOOLEAN NOT NULL DEFAULT false;