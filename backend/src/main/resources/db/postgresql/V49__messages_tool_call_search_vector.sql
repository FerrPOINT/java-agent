-- Keep session_search parity with Hermes: message FTS includes the visible
-- text plus tool-call metadata, so searches can find sessions by tool names
-- and arguments even when the assistant carrier message has empty content.
DROP INDEX IF EXISTS idx_messages_content_fts;

ALTER TABLE messages DROP COLUMN IF EXISTS content_tsv;

ALTER TABLE messages ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector(
            'english',
            coalesce(content, '') || ' ' ||
            coalesce(tool_call_name, '') || ' ' ||
            coalesce(tool_call_arguments, '') || ' ' ||
            coalesce(tool_calls, '')
        )
    ) STORED;

CREATE INDEX IF NOT EXISTS idx_messages_content_fts ON messages USING GIN (content_tsv);
