ALTER TABLE java_identity_reference_sources DISABLE TRIGGER java_identity_sources_immutable;
DO $$
BEGIN
    UPDATE java_identity_reference_sources
    SET user_columns = ARRAY['user_id']::text[]
    WHERE source = 'background_jobs';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Background job identity inventory is missing';
    END IF;
END
$$;
ALTER TABLE java_identity_reference_sources ENABLE TRIGGER java_identity_sources_immutable;
SELECT java_identity_install_guards();
