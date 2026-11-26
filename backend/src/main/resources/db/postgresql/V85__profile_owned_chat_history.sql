-- The ordinary worker sees one history namespace. The explicit dashboard
-- overview policy is read-only. The owner handles schema changes.
ALTER TABLE sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY session_profile ON sessions
    USING (profile = NULLIF(current_setting('pdlc.java.profile', true), ''))
    WITH CHECK (profile = NULLIF(current_setting('pdlc.java.profile', true), ''));
CREATE POLICY session_overview ON sessions FOR SELECT
    USING (current_setting('pdlc.java.profile', true) = '!history_overview');

CREATE FUNCTION java_history_parent_profile_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp
AS $body$
BEGIN
    IF NEW.parent_session_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.sessions parent
        WHERE parent.id = NEW.parent_session_id AND parent.profile = NEW.profile
    ) THEN
        RAISE EXCEPTION 'parent session is outside the selected profile'
            USING ERRCODE = '23514', CONSTRAINT = 'java_history_parent_profile_guard';
    END IF;
    RETURN NEW;
END;
$body$;
CREATE TRIGGER java_history_parent_profile_guard
    BEFORE INSERT OR UPDATE OF parent_session_id, profile ON sessions
    FOR EACH ROW EXECUTE FUNCTION java_history_parent_profile_guard();

ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
CREATE POLICY message_profile ON messages
    USING (EXISTS (
        SELECT 1 FROM sessions s WHERE s.id = messages.session_id
            AND s.profile = NULLIF(current_setting('pdlc.java.profile', true), '')
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM sessions s WHERE s.id = messages.session_id
            AND s.profile = NULLIF(current_setting('pdlc.java.profile', true), '')
    ));

ALTER TABLE session_cli_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY session_cli_state_profile ON session_cli_state
    USING (EXISTS (
        SELECT 1 FROM sessions s WHERE s.id = session_cli_state.session_id
            AND s.profile = NULLIF(current_setting('pdlc.java.profile', true), '')
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM sessions s WHERE s.id = session_cli_state.session_id
            AND s.profile = NULLIF(current_setting('pdlc.java.profile', true), '')
    ));
CREATE POLICY session_cli_state_overview ON session_cli_state FOR SELECT
    USING (current_setting('pdlc.java.profile', true) = '!history_overview'
        AND state_key IN ('heartbeat', 'browserModelConfigPresent'));

-- Preserve global identity-reference inventory as a bounded owner query.
ALTER FUNCTION java_identity_run_reference_count(text, text, text)
    RENAME TO java_identity_profile_reference_count;
CREATE OR REPLACE FUNCTION java_identity_profile_reference_count(source text, wanted_issuer text, wanted_subject text)
RETURNS TABLE(unresolved boolean, reference_count bigint)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
SET row_security = off
AS $body$
BEGIN
    IF source NOT IN ('openai_run_states', 'openai_run_events', 'sessions', 'messages', 'session_cli_state') THEN
        RAISE EXCEPTION 'invalid profile reference source' USING ERRCODE = '22023';
    END IF;

    RETURN QUERY EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM public.%1$I t
            CROSS JOIN LATERAL public.java_identity_row_refs(%2$L, to_jsonb(t)) r
            WHERE r.resolved IS NOT TRUE OR NULLIF(btrim(r.issuer), '''') IS NULL
                OR NULLIF(btrim(r.subject), '''') IS NULL
        ), (
            SELECT count(*) FROM public.%1$I t
            WHERE EXISTS (
                SELECT 1 FROM public.java_identity_row_refs(%2$L, to_jsonb(t)) r
                WHERE r.issuer = $1 AND r.subject = $2
            )
        )',
        source, source
    ) USING wanted_issuer, wanted_subject;
END;
$body$;
