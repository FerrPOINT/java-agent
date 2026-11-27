-- No row is visible without the profile set by the application transaction.
-- The migration owner retains custody for schema upgrades and the bounded
-- identity reference inventory. The application login must own neither table.
ALTER TABLE openai_run_states ENABLE ROW LEVEL SECURITY;
CREATE POLICY openai_run_profile ON openai_run_states
    USING (profile = NULLIF(current_setting('pdlc.java.profile', true), ''))
    WITH CHECK (profile = NULLIF(current_setting('pdlc.java.profile', true), ''));

ALTER TABLE openai_run_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY openai_run_event_profile ON openai_run_events
    USING (EXISTS (
        SELECT 1 FROM openai_run_states r
        WHERE r.run_id = openai_run_events.run_id
          AND r.profile = NULLIF(current_setting('pdlc.java.profile', true), '')
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM openai_run_states r
        WHERE r.run_id = openai_run_events.run_id
          AND r.profile = NULLIF(current_setting('pdlc.java.profile', true), '')
    ));

-- Identity deletion must see all references, including those from retired
-- profiles. Only counts and the unresolved-reference flag leave this function.
CREATE FUNCTION java_identity_run_reference_count(source text, wanted_issuer text, wanted_subject text)
RETURNS TABLE(unresolved boolean, reference_count bigint)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
SET row_security = off
AS $body$
BEGIN
    IF source NOT IN ('openai_run_states', 'openai_run_events') THEN
        RAISE EXCEPTION 'invalid native run reference source' USING ERRCODE = '22023';
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

REVOKE EXECUTE ON FUNCTION java_identity_run_reference_count(text, text, text) FROM PUBLIC;
