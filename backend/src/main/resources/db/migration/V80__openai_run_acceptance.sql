ALTER TABLE openai_run_states ADD COLUMN request_fingerprint VARCHAR(64);
ALTER TABLE openai_run_states ADD COLUMN accepted_session_id VARCHAR(256);
