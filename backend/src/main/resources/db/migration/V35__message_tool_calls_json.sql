-- A single assistant response may contain multiple tool calls. The original
-- scalar columns retain only the first and break the following tool results.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS tool_calls_json TEXT;