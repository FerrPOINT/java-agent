-- Pairing codes: code-based auth for unknown users
CREATE TABLE IF NOT EXISTS bot_pairing_codes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(8)  NOT NULL UNIQUE,
    user_id         VARCHAR(64) NOT NULL,
    chat_id         VARCHAR(64) NOT NULL,
    username        VARCHAR(128),
    status          VARCHAR(16) NOT NULL DEFAULT 'pending', -- pending | approved | denied | expired
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '1 hour')
);

CREATE INDEX IF NOT EXISTS idx_bot_pairing_codes_user_id ON bot_pairing_codes (user_id);
CREATE INDEX IF NOT EXISTS idx_bot_pairing_codes_status  ON bot_pairing_codes (status);
CREATE INDEX IF NOT EXISTS idx_bot_pairing_codes_code    ON bot_pairing_codes (code);