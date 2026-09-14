-- V62 (WP-11 / docs/35): attachment artifact metadata.
--
-- Metadata + safe cache reference ONLY (no blobs, no arbitrary paths in
-- session JSON). Blobs live under controlled cache roots with TTL cleanup;
-- this table records identity/hash, ownership linkage and disposition state.

CREATE TABLE IF NOT EXISTS attachment_artifacts (
    id             VARCHAR(64) PRIMARY KEY,
    owner_id       VARCHAR(128) NOT NULL DEFAULT 'system',
    profile        VARCHAR(64)  NOT NULL DEFAULT 'default',
    session_id     UUID,
    message_id     VARCHAR(128),
    content_hash   VARCHAR(64)  NOT NULL,
    origin         VARCHAR(16)  NOT NULL,          -- telegram | cli | internal
    disposition    VARCHAR(16)  NOT NULL,          -- photo | document | video | audio | voice | sticker | location | file
    mime_type      VARCHAR(128),
    file_name      VARCHAR(255),                   -- sanitized
    size_bytes     BIGINT,
    cache_path     TEXT,                           -- relative to controlled cache root, never absolute arbitrary
    state          VARCHAR(16)  NOT NULL DEFAULT 'received',  -- received | delivered | failed | expired
    lifetime_secs  INT NOT NULL DEFAULT 86400,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours',
    CONSTRAINT chk_attachment_origin CHECK (origin IN ('telegram', 'cli', 'internal')),
    CONSTRAINT chk_attachment_state CHECK (state IN ('received', 'delivered', 'failed', 'expired')),
    CONSTRAINT chk_attachment_disposition CHECK (disposition IN
        ('photo', 'document', 'video', 'audio', 'voice', 'sticker', 'location', 'file'))
);

-- dedupe: same owner + same content + same disposition is ONE artifact
CREATE UNIQUE INDEX IF NOT EXISTS uq_attachment_owner_hash_disposition
    ON attachment_artifacts (owner_id, content_hash, disposition);

CREATE INDEX IF NOT EXISTS idx_attachment_expiry
    ON attachment_artifacts (state, expires_at);

CREATE INDEX IF NOT EXISTS idx_attachment_session
    ON attachment_artifacts (session_id, created_at DESC);
