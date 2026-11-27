ALTER TABLE openai_run_states ADD COLUMN profile_revision VARCHAR(64);

ALTER TABLE openai_run_states ADD CONSTRAINT openai_run_profile_revision_check
    CHECK (profile_revision IS NULL OR
        (profile <> 'default' AND profile_revision ~ '^[0-9a-f]{64}$'));
