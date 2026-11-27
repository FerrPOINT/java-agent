-- The encoded memory storage key includes the profile as its final suffix.
-- Invalid historical namespaces remain inaccessible through a profile.
CREATE FUNCTION java_memory_profile(storage_key text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT
SET search_path = pg_catalog
AS $body$
    SELECT CASE
        WHEN strpos(storage_key, '::profile::') = 0 THEN 'default'
        ELSE substring(storage_key FROM '::profile::([a-z0-9][a-z0-9_-]{0,63})$')
    END
$body$;

ALTER TABLE memory ENABLE ROW LEVEL SECURITY;
CREATE POLICY memory_profile ON memory
    USING (java_memory_profile(user_id) = NULLIF(current_setting('pdlc.java.profile', true), ''))
    WITH CHECK (java_memory_profile(user_id) = NULLIF(current_setting('pdlc.java.profile', true), ''));

ALTER TABLE memory_pending ENABLE ROW LEVEL SECURITY;
CREATE POLICY memory_pending_profile ON memory_pending
    USING (java_memory_profile(user_id) = NULLIF(current_setting('pdlc.java.profile', true), ''))
    WITH CHECK (java_memory_profile(user_id) = NULLIF(current_setting('pdlc.java.profile', true), ''));

ALTER TABLE memory_write_audit ENABLE ROW LEVEL SECURITY;
CREATE POLICY memory_write_audit_profile ON memory_write_audit
    USING (java_memory_profile(user_id) = NULLIF(current_setting('pdlc.java.profile', true), ''))
    WITH CHECK (java_memory_profile(user_id) = NULLIF(current_setting('pdlc.java.profile', true), ''));

ALTER TABLE pending_review_summaries ENABLE ROW LEVEL SECURITY;
CREATE POLICY pending_review_summaries_profile ON pending_review_summaries
    USING (EXISTS (SELECT 1 FROM sessions s WHERE s.id=pending_review_summaries.session_id AND s.profile=NULLIF(current_setting('pdlc.java.profile', true), '')))
    WITH CHECK (EXISTS (SELECT 1 FROM sessions s WHERE s.id=pending_review_summaries.session_id AND s.profile=NULLIF(current_setting('pdlc.java.profile', true), '')));

ALTER TABLE attachment_artifacts ENABLE ROW LEVEL SECURITY;
CREATE POLICY attachment_artifacts_profile ON attachment_artifacts
    USING (profile = NULLIF(current_setting('pdlc.java.profile', true), ''))
    WITH CHECK (profile = NULLIF(current_setting('pdlc.java.profile', true), ''));

ALTER TABLE mcp_server_configs ENABLE ROW LEVEL SECURITY;
CREATE POLICY mcp_server_configs_profile ON mcp_server_configs
    USING (profile = NULLIF(current_setting('pdlc.java.profile', true), ''))
    WITH CHECK (profile = NULLIF(current_setting('pdlc.java.profile', true), ''));

ALTER TABLE mcp_schema_cache ENABLE ROW LEVEL SECURITY;
CREATE POLICY mcp_schema_cache_profile ON mcp_schema_cache
    USING (EXISTS (SELECT 1 FROM mcp_server_configs c WHERE c.id=mcp_schema_cache.server_config_id AND c.profile=NULLIF(current_setting('pdlc.java.profile', true), '')))
    WITH CHECK (EXISTS (SELECT 1 FROM mcp_server_configs c WHERE c.id=mcp_schema_cache.server_config_id AND c.profile=NULLIF(current_setting('pdlc.java.profile', true), '')));

ALTER TABLE mcp_oauth_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY mcp_oauth_tokens_profile ON mcp_oauth_tokens
    USING (profile = NULLIF(current_setting('pdlc.java.profile', true), ''))
    WITH CHECK (profile = NULLIF(current_setting('pdlc.java.profile', true), ''));

ALTER TABLE mcp_oauth_flows ENABLE ROW LEVEL SECURITY;
CREATE POLICY mcp_oauth_flows_profile ON mcp_oauth_flows
    USING (profile = NULLIF(current_setting('pdlc.java.profile', true), ''))
    WITH CHECK (profile = NULLIF(current_setting('pdlc.java.profile', true), ''));

-- The background retention job can remove expired artifacts under its
-- internal scope. It has no write access to another profile's live rows.
CREATE POLICY attachment_expired_read ON attachment_artifacts FOR SELECT
    USING (current_setting('pdlc.java.profile', true) = '!attachment_expiry'
        AND expires_at < statement_timestamp());
CREATE POLICY attachment_expired_delete ON attachment_artifacts FOR DELETE
    USING (current_setting('pdlc.java.profile', true) = '!attachment_expiry'
        AND expires_at < statement_timestamp());

-- Route an OAuth callback using its original opaque state without exposing
-- the flow, token, or PKCE verifier to an unscoped connection.
CREATE FUNCTION java_mcp_oauth_callback_profile(state_digest text, requested_server text) RETURNS text
LANGUAGE sql STABLE STRICT SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
SET row_security = off
AS $body$
    SELECT flow.profile FROM public.mcp_oauth_flows flow
    WHERE flow.state_hash = state_digest AND flow.server_name = requested_server
        AND flow.status = 'pending'
$body$;
REVOKE EXECUTE ON FUNCTION java_mcp_oauth_callback_profile(text, text) FROM PUBLIC;

-- The identity inventory still counts protected references across profiles.
CREATE OR REPLACE FUNCTION java_identity_profile_reference_count(source text, wanted_issuer text, wanted_subject text)
RETURNS TABLE(unresolved boolean, reference_count bigint)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
SET row_security = off
AS $body$
BEGIN
    IF source NOT IN ('openai_run_states', 'openai_run_events', 'sessions', 'messages', 'session_cli_state',
        'memory', 'memory_pending', 'memory_write_audit', 'pending_review_summaries', 'attachment_artifacts') THEN
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
