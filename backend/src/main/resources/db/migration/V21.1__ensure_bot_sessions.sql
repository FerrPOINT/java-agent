-- V21.1: ensure bot_sessions exists before V22 indexes it on fresh databases.
-- bot_sessions is owned by the telegram-bot module's Flyway history
-- (db/bot-migration), but on a fresh Docker Compose E2E the backend migrates
-- first. CREATE TABLE IF NOT EXISTS is a no-op where the bot already created it.
-- Schema mirrors telegram-bot/src/main/resources/db/bot-migration/V1__bot_schema.sql
-- (UUID default via hex_to_uuid for H2+PG portability; gen_random_uuid is PG-only).
CREATE TABLE IF NOT EXISTS bot_sessions (
    id              UUID PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    chat_id         VARCHAR(64)  NOT NULL,
    username        VARCHAR(128),
    title           VARCHAR(256),
    model_override  VARCHAR(128),
    yolo_mode       BOOLEAN      NOT NULL DEFAULT FALSE,
    verbose_mode    BOOLEAN      NOT NULL DEFAULT FALSE,
    fast_mode       BOOLEAN      NOT NULL DEFAULT FALSE,
    reasoning_level VARCHAR(16)  NOT NULL DEFAULT 'medium',
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_v21_1_bot_sessions_user ON bot_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_v21_1_bot_sessions_chat ON bot_sessions(chat_id);
