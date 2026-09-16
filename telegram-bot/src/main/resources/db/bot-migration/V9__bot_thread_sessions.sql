-- V9 (bot): thread-scoped sessions.
--
-- A group member's session was keyed by user alone, so every forum topic
-- (thread) in a room collapsed onto one backend transcript: thread B
-- resumed thread A's session and answered carrying A's context.
--
-- thread_id: Telegram message_thread_id for forum topics (NULL for DMs
-- and non-topic chats). Active-session identity becomes
-- (user_id, thread_id) instead of user_id alone.

ALTER TABLE bot_sessions
    ADD COLUMN IF NOT EXISTS thread_id BIGINT;

-- Backfill: existing rows keep NULL (DM-era sessions).
-- One active session per (user_id, thread_id) — partial unique index keeps
-- NULLs distinct (PostgreSQL semantics), so DM and topic sessions coexist.
CREATE UNIQUE INDEX IF NOT EXISTS uq_bot_sessions_user_thread_active
    ON bot_sessions (user_id, COALESCE(thread_id, -1))
    WHERE active = TRUE;
