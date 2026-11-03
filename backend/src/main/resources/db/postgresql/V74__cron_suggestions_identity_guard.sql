-- Extend the immutable identity inventory transactionally and reinstall guards.
ALTER TABLE java_identity_reference_sources DISABLE TRIGGER java_identity_sources_immutable;
INSERT INTO java_identity_reference_sources(source,user_columns,session_columns)
    VALUES ('cron_suggestions', ARRAY['user_id']::text[], ARRAY[]::text[]);
ALTER TABLE java_identity_reference_sources ENABLE TRIGGER java_identity_sources_immutable;
SELECT java_identity_install_guards();
