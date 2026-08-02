-- P0-3: Add CLI runtime settings support to sessions.
-- Stores per-session CLI state (reasoning effort, fast/voice mode, personality,
-- queued prompts, subgoals, browser CDP URL, disabled tools) and a subgoal column.

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS subgoal TEXT;

CREATE TABLE IF NOT EXISTS session_cli_state (
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    state_key TEXT NOT NULL,
    state_value TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, state_key)
);

CREATE INDEX IF NOT EXISTS idx_session_cli_state_session_id ON session_cli_state(session_id);
