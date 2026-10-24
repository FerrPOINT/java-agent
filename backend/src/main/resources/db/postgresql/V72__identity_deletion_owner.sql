-- Retain proven identity provenance and serialize all reference writers with deletion receipts.
-- Expiry-only request receipts are inserted/deleted in the same transaction.
-- Their deferred INSERT event retains the original request bound during COMMIT;
-- it never expires a persistent deletion fence or manufactures human authority.
CREATE TABLE java_identity_request_commit_fences (
    request_id uuid PRIMARY KEY,
    valid_until timestamptz NOT NULL
);
CREATE FUNCTION java_identity_request_commit_clock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF clock_timestamp() >= NEW.valid_until THEN
        RAISE EXCEPTION 'java_identity_request_authority_expired'
            USING ERRCODE='42501', CONSTRAINT='java_identity_request_commit_clock';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER java_identity_request_clock_immediate
BEFORE INSERT OR UPDATE ON java_identity_request_commit_fences
FOR EACH ROW EXECUTE FUNCTION java_identity_request_commit_clock();
CREATE CONSTRAINT TRIGGER java_identity_request_commit_clock
AFTER INSERT OR UPDATE ON java_identity_request_commit_fences
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION java_identity_request_commit_clock();

CREATE TABLE java_identity_issuer (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    issuer text NOT NULL CHECK(length(btrim(issuer)) BETWEEN 1 AND 2048)
);
CREATE TABLE java_identity_fence_epoch (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    revision bigint NOT NULL DEFAULT 1 CHECK(revision>0)
);
INSERT INTO java_identity_fence_epoch(singleton) VALUES(true);
CREATE SEQUENCE java_identity_reference_revision MINVALUE 1;
CREATE TABLE java_identity_deletion_operations (
    operation_id uuid PRIMARY KEY, issuer text NOT NULL, subject text NOT NULL,
    inventory_revision uuid NOT NULL, request jsonb NOT NULL,
    state text NOT NULL CHECK(state IN ('frozen','released')),
    fence uuid, revision bigint NOT NULL CHECK(revision>0),
    reference_count bigint NOT NULL CHECK(reference_count>=0),
    CHECK((state='frozen')=(fence IS NOT NULL))
);
CREATE UNIQUE INDEX java_identity_active_freeze ON java_identity_deletion_operations(issuer,subject) WHERE state='frozen';
CREATE TABLE java_identity_aliases (
    storage_key text PRIMARY KEY CHECK(length(btrim(storage_key)) BETWEEN 1 AND 1024),
    kind text NOT NULL CHECK(kind IN ('central','native')),
    issuer text, subject text, origin text NOT NULL CHECK(length(origin)>0),
    CHECK((kind='central' AND issuer IS NOT NULL AND length(btrim(issuer))>0 AND subject IS NOT NULL AND length(btrim(subject))>0)
       OR (kind='native' AND issuer IS NULL AND subject IS NULL))
);
CREATE TABLE java_identity_session_aliases (
    session_id uuid PRIMARY KEY,
    storage_key text NOT NULL CHECK(length(btrim(storage_key))>0)
);
-- Native user accounts and Telegram bot directory are product-owned authorities.
-- Missing legacy keys are deliberately not inferred from UUID or email shape.
INSERT INTO java_identity_aliases(storage_key,kind,origin)
    SELECT id,'native','native_agent_user' FROM agent_users;
INSERT INTO java_identity_aliases(storage_key,kind,origin)
    SELECT DISTINCT user_id,'native','native_bot_user' FROM bot_sessions
    WHERE length(btrim(user_id))>0 ON CONFLICT DO NOTHING;
-- The actual authenticated Telegram SessionResolver records both typed origin
-- columns and the numeric platform sender/chat key; this is native evidence,
-- unlike the shape of an arbitrary UUID in a legacy sessions.user_id.
INSERT INTO java_identity_aliases(storage_key,kind,origin)
    SELECT DISTINCT user_id,'native','native_telegram_gateway' FROM sessions
    WHERE source='telegram' AND origin_platform='telegram' AND user_id ~ '^-?[0-9]{1,20}$'
      AND (origin_user_id IS NULL OR origin_user_id=user_id) ON CONFLICT DO NOTHING;
-- These exact built-in noncentral IDs are declared by existing product writers.
INSERT INTO java_identity_aliases(storage_key,kind,origin)
    SELECT key,'native','builtin_native' FROM unnest(ARRAY['user-1','default','system','health','prometheus','info','dashboard','curator','agent']) key
    ON CONFLICT DO NOTHING;
INSERT INTO java_identity_session_aliases(session_id,storage_key) SELECT id,user_id FROM sessions;
CREATE TABLE java_identity_reference_sources (
    source text PRIMARY KEY, user_columns text[] NOT NULL, session_columns text[] NOT NULL, required boolean NOT NULL DEFAULT true
);
INSERT INTO java_identity_reference_sources(source,user_columns,session_columns) VALUES
    ('sessions',ARRAY['user_id','origin_user_id']::text[],ARRAY['parent_session_id']::text[]),
    ('memory',ARRAY['user_id']::text[],ARRAY[]::text[]),
    ('memory_pending',ARRAY['user_id']::text[],ARRAY[]::text[]),
    ('todos',ARRAY['user_id']::text[],ARRAY['session_id']::text[]),
    ('skills',ARRAY['user_id']::text[],ARRAY[]::text[]),
    ('cron_jobs',ARRAY['user_id']::text[],ARRAY['last_run_session_id','attached_session_id']::text[]),
    ('checkpoints',ARRAY['user_id']::text[],ARRAY[]::text[]),
    ('cron_execution_log',ARRAY['user_id']::text[],ARRAY[]::text[]),
    ('usage_log',ARRAY['user_id']::text[],ARRAY['session_id']::text[]),
    ('audit_log',ARRAY['user_id','actor']::text[],ARRAY['session_id']::text[]),
    ('skill_audit_log',ARRAY['user_id']::text[],ARRAY[]::text[]),
    ('delivery_work_items',ARRAY['user_id']::text[],ARRAY['parent_session_id']::text[]),
    ('gateway_home_channels',ARRAY['user_id','updated_by']::text[],ARRAY[]::text[]),
    ('outbound_message_receipts',ARRAY['user_id']::text[],ARRAY['session_id']::text[]),
    ('openai_run_states',ARRAY['user_id']::text[],ARRAY['session_id']::text[]),
    ('code_session_kernels',ARRAY['user_id']::text[],ARRAY['session_id']::text[]),
    ('console_tasks',ARRAY['user_id']::text[],ARRAY['session_id']::text[]),
    ('memory_write_audit',ARRAY['user_id']::text[],ARRAY['source_session_id','parent_session_id']::text[]),
    ('attachment_artifacts',ARRAY['owner_id']::text[],ARRAY['session_id']::text[]),
    ('messages',ARRAY[]::text[],ARRAY['session_id']::text[]),
    ('session_cli_state',ARRAY[]::text[],ARRAY['session_id']::text[]),
    ('compression_locks',ARRAY[]::text[],ARRAY['session_id']::text[]),
    ('background_jobs',ARRAY[]::text[],ARRAY['session_id']::text[]),
    ('openai_responses',ARRAY[]::text[],ARRAY['session_id']::text[]),
    ('delegated_task_runs',ARRAY[]::text[],ARRAY['parent_session_id','child_session_id']::text[]),
    ('pending_review_summaries',ARRAY[]::text[],ARRAY['session_id']::text[]),
    ('checkpoint_files',ARRAY[]::text[],ARRAY[]::text[]),
    ('console_task_output',ARRAY[]::text[],ARRAY[]::text[]),
    ('openai_response_conversations',ARRAY[]::text[],ARRAY[]::text[]),
    ('openai_run_events',ARRAY[]::text[],ARRAY[]::text[]),
    ('dashboard_actions',ARRAY['actor']::text[],ARRAY[]::text[]),
    ('profile_config_revisions',ARRAY['actor']::text[],ARRAY[]::text[]),
    ('agent_users',ARRAY[]::text[],ARRAY[]::text[]),
    ('user_api_keys',ARRAY[]::text[],ARRAY[]::text[]),
    ('bot_sessions',ARRAY[]::text[],ARRAY['backend_session_id']::text[]),
    ('java_identity_aliases',ARRAY[]::text[],ARRAY[]::text[]),
    ('java_identity_session_aliases',ARRAY[]::text[],ARRAY[]::text[]);

INSERT INTO java_identity_reference_sources(source,user_columns,session_columns,required) VALUES
    ('review_delivery_receipts',ARRAY[]::text[],ARRAY['backend_session_id']::text[],false),
    ('bot_messages',ARRAY[]::text[],ARRAY[]::text[],false);

CREATE FUNCTION java_identity_write_lock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock_shared(714402216006::bigint);
    PERFORM singleton FROM java_identity_fence_epoch WHERE singleton FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'identity_fence_epoch_missing' USING ERRCODE='23514'; END IF;
    PERFORM nextval('java_identity_reference_revision');
    RETURN NULL;
END;
$$;
CREATE FUNCTION java_identity_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'identity_provenance_immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER java_identity_sources_immutable BEFORE INSERT OR UPDATE OR DELETE ON java_identity_reference_sources
    FOR EACH STATEMENT EXECUTE FUNCTION java_identity_immutable();
CREATE TRIGGER java_identity_alias_immutable BEFORE UPDATE OR DELETE ON java_identity_aliases
    FOR EACH ROW EXECUTE FUNCTION java_identity_immutable();
CREATE TRIGGER java_identity_session_alias_immutable BEFORE UPDATE OR DELETE ON java_identity_session_aliases
    FOR EACH ROW EXECUTE FUNCTION java_identity_immutable();
CREATE FUNCTION java_identity_bind_issuer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'identity_issuer_immutable' USING ERRCODE='23514';END IF;
    PERFORM pg_advisory_xact_lock(714402216006::bigint);
    UPDATE java_identity_fence_epoch SET revision=revision+1 WHERE singleton;
    IF NOT FOUND THEN RAISE EXCEPTION 'identity_fence_epoch_missing' USING ERRCODE='23514';END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER java_identity_bind_issuer BEFORE INSERT OR UPDATE OR DELETE ON java_identity_issuer
    FOR EACH ROW EXECUTE FUNCTION java_identity_bind_issuer();
CREATE FUNCTION java_identity_storage_refs(key text)
RETURNS TABLE(issuer text,subject text,resolved boolean) LANGUAGE plpgsql STABLE AS $$
DECLARE binding java_identity_aliases%ROWTYPE;base_key text;profile text;
BEGIN
    IF key IS NULL THEN RETURN;END IF;
    SELECT * INTO binding FROM java_identity_aliases a WHERE a.storage_key=key;
    IF NOT FOUND AND key LIKE '%::profile::%' THEN
        base_key:=split_part(key,'::profile::',1);profile:=split_part(key,'::profile::',2);
        -- MemoryScope creates exactly one normalized profile suffix.
        IF (profile ~ '^[a-z0-9][a-z0-9_-]{0,63}$' OR profile='__invalid_profile__') AND key=base_key||'::profile::'||profile THEN
            SELECT * INTO binding FROM java_identity_aliases a WHERE a.storage_key=base_key;
        END IF;
    END IF;
    IF binding.storage_key IS NULL THEN issuer:=NULL;subject:=key;resolved:=false;RETURN NEXT;RETURN;END IF;
    IF binding.kind='central' THEN issuer:=binding.issuer;subject:=binding.subject;resolved:=true;RETURN NEXT;END IF;
END;
$$;
CREATE FUNCTION java_identity_session_refs(key text)
RETURNS TABLE(issuer text,subject text,resolved boolean) LANGUAGE plpgsql STABLE AS $$
DECLARE storage text;
BEGIN
    IF key IS NULL THEN RETURN;END IF;
    IF key !~ '^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$' THEN
        issuer:=NULL;subject:=NULL;resolved:=false;RETURN NEXT;RETURN;
    END IF;
    SELECT a.storage_key INTO storage FROM java_identity_session_aliases a WHERE session_id=key::uuid;
    IF NOT FOUND THEN issuer:=NULL;subject:=NULL;resolved:=false;RETURN NEXT;RETURN;END IF;
    RETURN QUERY SELECT * FROM java_identity_storage_refs(storage);
END;
$$;
CREATE FUNCTION java_identity_parent_refs(source_name text,column_name text,key text)
RETURNS TABLE(issuer text,subject text,resolved boolean) LANGUAGE plpgsql STABLE AS $$
DECLARE parent jsonb;
BEGIN
    IF key IS NULL THEN RETURN;END IF;
    IF NOT ((source_name='checkpoints' AND column_name='id') OR (source_name='console_tasks' AND column_name='id')
        OR (source_name='openai_responses' AND column_name='response_id') OR (source_name='openai_run_states' AND column_name='run_id')
        OR (source_name='bot_sessions' AND column_name='id')) THEN RAISE EXCEPTION 'identity_parent_source_unknown';END IF;
    EXECUTE format('SELECT to_jsonb(t) FROM %I t WHERE %I::text=$1',source_name,column_name) INTO parent USING key;
    IF parent IS NULL THEN issuer:=NULL;subject:=NULL;resolved:=false;RETURN NEXT;RETURN;END IF;
    RETURN QUERY SELECT * FROM java_identity_row_refs(source_name,parent);
END;
$$;

CREATE FUNCTION java_identity_row_refs(source_name text,document jsonb)
RETURNS TABLE(issuer text,subject text,resolved boolean) LANGUAGE plpgsql STABLE AS $$
DECLARE inventory java_identity_reference_sources%ROWTYPE;key text;
BEGIN
    SELECT * INTO inventory FROM java_identity_reference_sources WHERE source=source_name;
    IF NOT FOUND THEN RAISE EXCEPTION 'identity_source_unknown';END IF;
    IF source_name='java_identity_aliases' AND document->>'kind'='central' THEN
        issuer:=document->>'issuer';subject:=document->>'subject';resolved:=true;RETURN NEXT;
    ELSIF source_name='java_identity_session_aliases' THEN
        RETURN QUERY SELECT * FROM java_identity_storage_refs(document->>'storage_key');
    END IF;
    CASE source_name
        WHEN 'checkpoint_files' THEN RETURN QUERY SELECT * FROM java_identity_parent_refs('checkpoints','id',document->>'checkpoint_id');
        WHEN 'console_task_output' THEN RETURN QUERY SELECT * FROM java_identity_parent_refs('console_tasks','id',document->>'task_id');
        WHEN 'openai_response_conversations' THEN RETURN QUERY SELECT * FROM java_identity_parent_refs('openai_responses','response_id',document->>'response_id');
        WHEN 'openai_run_events' THEN RETURN QUERY SELECT * FROM java_identity_parent_refs('openai_run_states','run_id',document->>'run_id');
        WHEN 'bot_messages' THEN RETURN QUERY SELECT * FROM java_identity_parent_refs('bot_sessions','id',document->>'session_id');
        ELSE NULL;
    END CASE;
    FOREACH key IN ARRAY inventory.user_columns LOOP
        RETURN QUERY SELECT * FROM java_identity_storage_refs(document->>key);
    END LOOP;
    FOREACH key IN ARRAY inventory.session_columns LOOP
        RETURN QUERY SELECT * FROM java_identity_session_refs(document->>key);
    END LOOP;
END;
$$;
CREATE FUNCTION java_identity_reference_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE document jsonb;
BEGIN
    document:=to_jsonb(NEW);
    -- A deployment without a bound central issuer remains a standalone native
    -- runtime. Binding is explicit; thereafter ambiguous references fail closed.
    IF EXISTS(SELECT 1 FROM java_identity_issuer) AND EXISTS(
        SELECT 1 FROM java_identity_row_refs(TG_TABLE_NAME,document) r
        WHERE r.resolved IS NOT TRUE OR NULLIF(btrim(r.issuer),'') IS NULL OR NULLIF(btrim(r.subject),'') IS NULL
    ) THEN RAISE EXCEPTION 'identity_reference_unknown' USING ERRCODE='23514',CONSTRAINT='java_identity_reference_unknown';END IF;
    IF EXISTS(SELECT 1 FROM java_identity_row_refs(TG_TABLE_NAME,document) r
        JOIN java_identity_deletion_operations f ON f.issuer=r.issuer AND f.subject=r.subject WHERE f.state='frozen') THEN
        RAISE EXCEPTION 'identity_deletion_fenced' USING ERRCODE='23514',CONSTRAINT='java_identity_deletion_fenced';
    END IF;
    RETURN NEW;
END;
$$;
-- Only the actual authenticated native JDBC transaction can establish a new
-- native storage name. Existing central provenance is never rebound or hidden.
CREATE FUNCTION java_identity_capture_native_origin() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE inventory java_identity_reference_sources%ROWTYPE;document jsonb;column_name text;
    key text;base_key text;profile text;principal text;kind text;namespace text;
BEGIN
    IF TG_TABLE_NAME IN ('java_identity_aliases','java_identity_session_aliases') THEN RETURN NEW;END IF;
    kind:=current_setting('pdlc.java.native_kind',true);
    principal:=current_setting('pdlc.java.native_principal',true);
    namespace:=current_setting('pdlc.java.native_namespace',true);
    IF kind IS NULL OR kind NOT IN ('DEVELOPMENT','GATEWAY_KEY','USER_API_KEY','GATEWAY_EVENT')
        OR principal IS NULL OR length(btrim(principal))=0 OR namespace IS NULL OR namespace NOT IN ('true','false') THEN RETURN NEW;END IF;
    SELECT * INTO inventory FROM java_identity_reference_sources WHERE source=TG_TABLE_NAME;
    IF NOT FOUND THEN RAISE EXCEPTION 'identity_source_unknown';END IF;
    document:=to_jsonb(NEW);
    FOREACH column_name IN ARRAY inventory.user_columns LOOP
        key:=document->>column_name;
        IF key IS NULL OR length(btrim(key))=0 THEN CONTINUE;END IF;
        base_key:=key;
        IF key LIKE '%::profile::%' THEN
            base_key:=split_part(key,'::profile::',1);profile:=split_part(key,'::profile::',2);
            IF NOT ((profile ~ '^[a-z0-9][a-z0-9_-]{0,63}$' OR profile='__invalid_profile__')
                AND key=base_key||'::profile::'||profile) THEN CONTINUE;END IF;
        END IF;
        IF length(btrim(base_key))>0 AND (namespace='true' OR base_key=principal) THEN
            INSERT INTO java_identity_aliases(storage_key,kind,origin)
                VALUES(base_key,'native','verified_native_'||lower(kind)) ON CONFLICT DO NOTHING;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE FUNCTION java_identity_capture_native() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE key text;binding java_identity_aliases%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME='agent_users' THEN key:=NEW.id;ELSE key:=NEW.user_id;END IF;
    IF key IS NULL OR length(btrim(key))=0 THEN RETURN NEW;END IF;
    INSERT INTO java_identity_aliases(storage_key,kind,origin) VALUES(key,'native',TG_TABLE_NAME)
        ON CONFLICT DO NOTHING;
    SELECT * INTO binding FROM java_identity_aliases WHERE storage_key=key;
    IF binding.kind<>'native' THEN RAISE EXCEPTION 'identity_alias_rebind_denied' USING ERRCODE='23514';END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER java_identity_capture_native_user BEFORE INSERT OR UPDATE ON agent_users
    FOR EACH ROW EXECUTE FUNCTION java_identity_capture_native();
CREATE TRIGGER java_identity_capture_native_bot BEFORE INSERT OR UPDATE ON bot_sessions
    FOR EACH ROW EXECUTE FUNCTION java_identity_capture_native();
CREATE FUNCTION java_identity_capture_session() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous text;
BEGIN
    INSERT INTO java_identity_session_aliases(session_id,storage_key) VALUES(NEW.id,NEW.user_id) ON CONFLICT DO NOTHING;
    SELECT storage_key INTO previous FROM java_identity_session_aliases WHERE session_id=NEW.id;
    IF previous IS DISTINCT FROM NEW.user_id OR (TG_OP='UPDATE' AND OLD.id IS DISTINCT FROM NEW.id) THEN
        RAISE EXCEPTION 'identity_session_rebind_denied' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER java_identity_capture_session BEFORE INSERT OR UPDATE ON sessions
    FOR EACH ROW EXECUTE FUNCTION java_identity_capture_session();
-- Called in the same Flyway transaction when optional Telegram tables appear.
CREATE FUNCTION java_identity_install_guards() RETURNS void LANGUAGE plpgsql AS $$
DECLARE item record;
BEGIN
    FOR item IN SELECT source,required FROM java_identity_reference_sources LOOP
        IF to_regclass(item.source) IS NULL THEN
            IF item.required THEN RAISE EXCEPTION 'identity_source_missing';END IF;
            CONTINUE;
        END IF;
        IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=to_regclass(item.source) AND tgname='a_identity_reference_lock') THEN
            EXECUTE format('CREATE TRIGGER a_identity_reference_lock BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH STATEMENT EXECUTE FUNCTION java_identity_write_lock()',item.source);
        END IF;
        IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=to_regclass(item.source) AND tgname='z_identity_reference_guard') THEN
            EXECUTE format('CREATE TRIGGER z_identity_reference_guard BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION java_identity_reference_guard()',item.source);
        END IF;
        IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=to_regclass(item.source) AND tgname='b_identity_native_origin') THEN
            EXECUTE format('CREATE TRIGGER b_identity_native_origin BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION java_identity_capture_native_origin()',item.source);
        END IF;
    END LOOP;
END;
$$;
SELECT java_identity_install_guards();
