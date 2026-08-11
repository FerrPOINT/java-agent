-- Sticker cache: cache sticker descriptions by file_unique_id
CREATE TABLE IF NOT EXISTS bot_sticker_cache (
    file_unique_id   VARCHAR(128) PRIMARY KEY,
    description      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);