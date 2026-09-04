-- V49 indexed the wrong column: Hibernate maps MessageEntity.toolCallsJson to
-- tool_calls_json, while V41's legacy tool_calls stayed empty. Rebuild the FTS
-- vector over the column the application actually writes, backfill included.
DROP INDEX IF EXISTS idx_messages_content_fts;

ALTER TABLE messages DROP COLUMN IF EXISTS content_tsv;

-- Backfill: the legacy duplicate column must mirror tool_calls_json so any
-- external reader of tool_calls stays consistent.
UPDATE messages SET tool_calls = tool_calls_json
 WHERE tool_calls IS NULL AND tool_calls_json IS NOT NULL;

ALTER TABLE messages ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector(
            'english',
            coalesce(content, '') || ' ' ||
            coalesce(tool_call_name, '') || ' ' ||
            coalesce(tool_call_arguments, '') || ' ' ||
            coalesce(tool_calls_json, '')
        )
    ) STORED;

CREATE INDEX IF NOT EXISTS idx_messages_content_fts ON messages USING GIN (content_tsv);
