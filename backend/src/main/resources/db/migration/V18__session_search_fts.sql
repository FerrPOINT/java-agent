-- V18: Add full-text search support for sessions and messages.
-- Creates GIN indexes on tsvector columns derived from session titles
-- and message content, enabling efficient FTS via to_tsqueryplainto_tsquery.

-- Add a generated tsvector column on sessions.title for FTS
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS title_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(title, ''))) STORED;

CREATE INDEX IF NOT EXISTS idx_sessions_title_fts ON sessions USING GIN (title_tsv);

-- Add a generated tsvector column on messages.content for FTS
ALTER TABLE messages ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED;

CREATE INDEX IF NOT EXISTS idx_messages_content_fts ON messages USING GIN (content_tsv);