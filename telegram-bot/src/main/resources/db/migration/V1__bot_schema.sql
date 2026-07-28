-- Telegram bot schema
CREATE TABLE IF NOT EXISTS bot_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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

CREATE INDEX IF NOT EXISTS idx_bot_sessions_user_id    ON bot_sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_bot_sessions_chat_id    ON bot_sessions (chat_id);
CREATE INDEX IF NOT EXISTS idx_bot_sessions_active     ON bot_sessions (active);

CREATE TABLE IF NOT EXISTS bot_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID         NOT NULL REFERENCES bot_sessions(id) ON DELETE CASCADE,
    role            VARCHAR(16)  NOT NULL,
    content         TEXT,
    telegram_msg_id BIGINT,
    turn_index      INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bot_messages_session_id ON bot_messages (session_id);

CREATE TABLE IF NOT EXISTS bot_media_cache (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id         VARCHAR(512) NOT NULL UNIQUE,
    file_path       VARCHAR(1024),
    local_path      VARCHAR(1024),
    mime_type       VARCHAR(128),
    file_size       BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL DEFAULT (NOW() + INTERVAL '24 hours')
);

CREATE INDEX IF NOT EXISTS idx_bot_media_file_id ON bot_media_cache (file_id);