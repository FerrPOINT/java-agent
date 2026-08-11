-- V6: Persist previously-transient fields (suspended, resume_pending, metadata) on bot_sessions
ALTER TABLE bot_sessions ADD COLUMN IF NOT EXISTS suspended BOOLEAN DEFAULT FALSE;
ALTER TABLE bot_sessions ADD COLUMN IF NOT EXISTS resume_pending BOOLEAN DEFAULT FALSE;
ALTER TABLE bot_sessions ADD COLUMN IF NOT EXISTS metadata TEXT;