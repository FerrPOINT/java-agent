-- Background jobs can be sessionless, so session_id alone cannot enforce tenant isolation.
ALTER TABLE background_jobs
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(128);

UPDATE background_jobs job
SET user_id = session_owner.user_id
FROM sessions session_owner
WHERE job.user_id IS NULL
  AND job.session_id = session_owner.id;

UPDATE background_jobs
SET user_id = 'system'
WHERE user_id IS NULL;

ALTER TABLE background_jobs
    ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_background_jobs_user_created
    ON background_jobs (user_id, created_at DESC);
