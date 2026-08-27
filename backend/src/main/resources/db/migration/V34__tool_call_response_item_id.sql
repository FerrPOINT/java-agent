-- P-01 (Hermes parity audit 2026-08-27): Responses providers can expose the
-- pairing id and the response item id separately; histories reloaded from
-- persistence must keep the alias so tool results still pair with their
-- assistant tool_call after compression/rotation/replay.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS tool_response_item_id TEXT;
