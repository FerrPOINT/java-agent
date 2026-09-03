-- Durable OpenAI Responses API state, mirroring Hermes response_store.db.
CREATE TABLE IF NOT EXISTS openai_responses (
    response_id TEXT PRIMARY KEY,
    response_json TEXT NOT NULL,
    conversation_history_json TEXT NOT NULL,
    instructions TEXT,
    session_id UUID REFERENCES sessions(id) ON DELETE SET NULL,
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_openai_responses_accessed_at
    ON openai_responses(accessed_at, response_id);

CREATE TABLE IF NOT EXISTS openai_response_conversations (
    name TEXT PRIMARY KEY,
    response_id TEXT NOT NULL REFERENCES openai_responses(response_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_openai_response_conversations_response_id
    ON openai_response_conversations(response_id);
