ALTER TABLE background_jobs ADD COLUMN user_id TEXT;

-- Session-bound history has a known owner; sessionless history stays unattributed.
UPDATE background_jobs
SET user_id = (SELECT sessions.user_id FROM sessions WHERE sessions.id = background_jobs.session_id)
WHERE user_id IS NULL AND session_id IS NOT NULL;

CREATE INDEX idx_background_jobs_user_id ON background_jobs (user_id);
