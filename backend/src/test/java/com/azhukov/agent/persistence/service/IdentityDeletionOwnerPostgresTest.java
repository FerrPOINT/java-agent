package com.azhukov.agent.persistence.service;

import com.azhukov.agent.config.NativeIdentityDataSource;
import com.azhukov.agent.core.model.IdentityDeletionProtocol.*;
import com.azhukov.agent.core.security.NativeIdentityOrigin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** Mandatory dedicated PostgreSQL proof; missing configuration is a failure, not a skip. */
@Tag("slow")
class IdentityDeletionOwnerPostgresTest {
    private static final String ISSUER="https://auth.example.test";
    private static Window window(){return new Window(System.nanoTime()+Duration.ofSeconds(30).toNanos(),Instant.now().plusSeconds(30));}
    private static Request request(){return new Request(1,UUID.randomUUID(),new Identity(ISSUER,UUID.randomUUID().toString()),UUID.randomUUID());}
    private interface Work{void run(DataSource db)throws Exception;}
    private static void isolated(boolean predecessor,Work work)throws Exception {
        String url=System.getenv("JAVA_IDENTITY_TEST_JDBC_URL");
        assertThat(url).as("dedicated JAVA_IDENTITY_TEST_JDBC_URL required").isNotBlank();
        var base=new DriverManagerDataSource(url);
        String schema="identity_owner_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=base.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT current_database()")) {
            assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("java_identity_test");
        }
        try(Connection c=base.getConnection();var q=c.createStatement()){q.execute("CREATE SCHEMA "+schema);}
        DataSource db=new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException{Connection c=base.getConnection();try(var q=c.createStatement()){q.execute("SET search_path TO "+schema);}return c;}
            @Override public Connection getConnection(String user,String password)throws SQLException{return getConnection();}
        };
        try {
            var config=Flyway.configure().dataSource(db).locations("classpath:db/migration","classpath:db/postgresql").outOfOrder(true);
            if(predecessor)config.target(MigrationVersion.fromVersion("71"));
            config.load().migrate();work.run(db);
        } finally {
            NativeIdentityOrigin.clear();
            try(Connection c=base.getConnection();var q=c.createStatement()){q.execute("SET statement_timeout='15s'");q.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    private static IdentityDeletionStore store(DataSource db){
        var environment=new MockEnvironment().withProperty("agent.central-auth.issuer",ISSUER).withProperty("agent.identity-deletion.legacy-issuer",ISSUER);
        var store=new IdentityDeletionStore(db,environment,new ObjectMapper());store.init();return store;
    }
    private static void sql(DataSource db,String text)throws SQLException{try(Connection c=db.getConnection();var q=c.createStatement()){q.execute(text);}}
    private static long scalar(DataSource db,String text)throws SQLException{try(Connection c=db.getConnection();var q=c.createStatement();var r=q.executeQuery(text)){r.next();return r.getLong(1);}}
    private static String text(DataSource db,String sql)throws SQLException{try(Connection c=db.getConnection();var q=c.createStatement();var r=q.executeQuery(sql)){r.next();return r.getString(1);}}
    private static int pid(Connection c)throws SQLException{try(var q=c.createStatement();var r=q.executeQuery("SELECT pg_backend_pid()")){r.next();return r.getInt(1);}}
    private static void blockedBy(DataSource db,int blocker)throws Exception {
        for(int attempt=0;attempt<500;attempt++) {
            if(scalar(db,"SELECT count(*) FROM pg_stat_activity WHERE "+blocker+"=ANY(pg_blocking_pids(pid))")>0)return;
            Thread.sleep(20);
        }
        throw new AssertionError("owner did not reach the actual PostgreSQL barrier");
    }
    private static void session(DataSource db,UUID id,String user)throws SQLException {
        try(Connection c=db.getConnection();var q=c.prepareStatement("INSERT INTO sessions(id,user_id,model_provider,model_name) VALUES(?,?, 'noop','synthetic')")){q.setObject(1,id);q.setString(2,user);q.executeUpdate();}
    }
    @Test void exact_replay_competing_operation_and_cancel_before_late_freeze()throws Exception{isolated(false,db->{
        var owner=store(db);Request r=request();Freeze receipt=owner.freeze(r,window());assertThat(receipt.referenceCount()).isZero();
        assertThat(owner.freeze(r,window())).isEqualTo(receipt);
        var other=new Request(1,UUID.randomUUID(),r.identity(),r.inventoryRevision());
        assertThatThrownBy(()->owner.freeze(other,window())).isInstanceOfSatisfying(Failure.class,e->assertThat(e.status()).isEqualTo(409));
        var conflict=new Request(1,r.operationId(),r.identity(),UUID.randomUUID());assertThatThrownBy(()->owner.release(conflict,window())).isInstanceOf(Failure.class);
        assertThat(owner.release(r,window()).cancelled()).isTrue();assertThatThrownBy(()->owner.freeze(r,window())).isInstanceOf(Failure.class);
        Request late=request();owner.release(late,window());assertThatThrownBy(()->owner.freeze(late,window())).isInstanceOf(Failure.class);
    });}
    @Test void actual_central_user_profile_memory_and_session_children_are_fenced()throws Exception{isolated(false,db->{
        var owner=store(db);Request r=request();owner.central(ISSUER,r.identity().subject(),window());UUID session=UUID.randomUUID();session(db,session,r.identity().subject());
        sql(db,"INSERT INTO messages(session_id,turn_index,role,content) VALUES('"+session+"',0,'user','synthetic')");
        long before=owner.check(r,window()).referenceCount();assertThat(before).isGreaterThanOrEqualTo(3);
        owner.freeze(r,window());
        assertThatThrownBy(()->session(db,UUID.randomUUID(),r.identity().subject())).isInstanceOf(SQLException.class).hasMessageContaining("identity_deletion_fenced");
        assertThatThrownBy(()->sql(db,"INSERT INTO messages(session_id,turn_index,role,content) VALUES('"+session+"',1,'user','synthetic')")).isInstanceOf(SQLException.class).hasMessageContaining("identity_deletion_fenced");
        assertThatThrownBy(()->sql(db,"INSERT INTO memory(user_id,fact) VALUES('"+r.identity().subject()+"::profile::qa','synthetic')")).isInstanceOf(SQLException.class).hasMessageContaining("identity_deletion_fenced");
        owner.release(r,window());sql(db,"DELETE FROM sessions WHERE id='"+session+"'");
        assertThat(owner.check(new Request(1,UUID.randomUUID(),r.identity(),r.inventoryRevision()),window()).referenceCount()).isPositive();
    });}
    @Test void native_uuid_and_native_gateway_never_become_a_guessed_auth_subject()throws Exception{isolated(false,db->{
        var owner=store(db);Request r=request();String id=r.identity().subject();
        sql(db,"INSERT INTO agent_users(id,username,role) VALUES('"+id+"','synthetic-native','user')");session(db,UUID.randomUUID(),id);
        assertThat(owner.check(r,window()).referenceCount()).isZero();
        assertThatThrownBy(()->owner.central(ISSUER,id,window())).isInstanceOf(Failure.class);
        NativeIdentityOrigin.bind(new NativeIdentityOrigin.Origin(NativeIdentityOrigin.Kind.GATEWAY_KEY,"user-1",true));
        owner.nativeStorage("telegram-actual-native-sender");session(db,UUID.randomUUID(),"telegram-actual-native-sender");
        assertThat(owner.check(request(),window()).referenceCount()).isZero();
    });}
    @Test void populated_legacy_is_preserved_and_ambiguous_provenance_is_closed()throws Exception{isolated(true,db->{
        assertThat(scalar(db,"SELECT max(version::numeric) FROM flyway_schema_history WHERE success AND type='SQL'")).isEqualTo(71);
        for(String removed:List.of("context_references","approvals","session_model_usage")) {
            assertThat(scalar(db,"SELECT CASE WHEN to_regclass('"+removed+"') IS NULL THEN 1 ELSE 0 END")).as("released V23 removed %s",removed).isEqualTo(1);
        }
        UUID session=UUID.randomUUID();session(db,session,"legacy-ambiguous-storage-key");
        UUID nativeSession=UUID.randomUUID();session(db,nativeSession,"123456");
        sql(db,"UPDATE sessions SET source='telegram',origin_platform='telegram',origin_user_id='123456' WHERE id='"+nativeSession+"'");
        Flyway.configure().dataSource(db).locations("classpath:db/migration","classpath:db/postgresql").outOfOrder(true).load().migrate();
        assertThat(scalar(db,"SELECT count(*) FROM flyway_schema_history WHERE success AND version='72'")).isEqualTo(1);
        assertThat(scalar(db,"SELECT count(*) FROM java_identity_reference_sources WHERE source IN ('context_references','approvals','session_model_usage')")).isZero();
        assertThat(scalar(db,"SELECT count(*) FROM java_identity_reference_sources WHERE source='memory' AND required")).isEqualTo(1);
        assertThat(scalar(db,"SELECT count(*) FROM sessions WHERE id='"+session+"' AND user_id='legacy-ambiguous-storage-key'")).isEqualTo(1);
        assertThat(scalar(db,"SELECT count(*) FROM java_identity_aliases WHERE storage_key='123456' AND kind='native' AND origin='native_telegram_gateway'")).isEqualTo(1);
        var owner=store(db);assertThatThrownBy(()->owner.check(request(),window())).isInstanceOfSatisfying(Failure.class,e->assertThat(e.status()).isEqualTo(503));
        // Explicit trusted operator provenance, not a UUID/email inference.
        sql(db,"INSERT INTO java_identity_aliases(storage_key,kind,origin) VALUES('legacy-ambiguous-storage-key','native','operator_proven_native')");
        assertThat(owner.check(request(),window()).referenceCount()).isZero();
        assertThatThrownBy(()->sql(db,"UPDATE java_identity_aliases SET kind='central',issuer='"+ISSUER+"',subject='"+UUID.randomUUID()+"' WHERE storage_key='legacy-ambiguous-storage-key'")).isInstanceOf(SQLException.class).hasMessageContaining("identity_provenance_immutable");
    });}
    @Test void missing_epoch_or_guard_and_original_expiry_never_persist_a_fence()throws Exception{
        isolated(true,db->{
            assertThat(scalar(db,"SELECT max(version::numeric) FROM flyway_schema_history WHERE success AND type='SQL'")).isEqualTo(71);
            sql(db,"DROP TABLE memory");
            Throwable failure=catchThrowable(()->Flyway.configure().dataSource(db).locations("classpath:db/migration","classpath:db/postgresql").outOfOrder(true).load().migrate());
            assertThat(failure).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
            while(failure.getCause()!=null)failure=failure.getCause();
            assertThat(failure).isInstanceOfSatisfying(SQLException.class,error->{
                assertThat(error.getSQLState()).isEqualTo("P0001");
                assertThat(error.getMessage()).contains("identity_source_missing");
            });
            assertThat(scalar(db,"SELECT count(*) FROM flyway_schema_history WHERE success AND version='72'")).isZero();
            assertThat(scalar(db,"SELECT CASE WHEN to_regclass('java_identity_reference_sources') IS NULL THEN 1 ELSE 0 END")).isEqualTo(1);
        });
        isolated(false,db->{
        var owner=store(db);Request r=request();
        sql(db,"ALTER TABLE memory RENAME TO qa_missing_memory");
        assertThatThrownBy(()->owner.check(r,window())).isInstanceOfSatisfying(Failure.class,error->assertThat(error.status()).isEqualTo(503));
        assertThatThrownBy(()->sql(db,"SELECT java_identity_install_guards()"))
            .isInstanceOfSatisfying(SQLException.class,error->{
                assertThat(error.getSQLState()).isEqualTo("P0001");
                assertThat(error.getMessage()).contains("identity_source_missing");
            });
        sql(db,"ALTER TABLE qa_missing_memory RENAME TO memory");
        assertThat(owner.check(r,window()).referenceCount()).isZero();
        assertThatThrownBy(()->owner.freeze(r,new Window(System.nanoTime()-1,Instant.now().plusSeconds(60)))).isInstanceOf(Failure.class);
        assertThat(scalar(db,"SELECT count(*) FROM java_identity_deletion_operations")).isZero();
        sql(db,"DROP TRIGGER z_identity_reference_guard ON messages");assertThatThrownBy(()->owner.check(r,window())).isInstanceOf(Failure.class);
        sql(db,"SELECT java_identity_install_guards();DELETE FROM java_identity_fence_epoch");
        assertThatThrownBy(()->owner.freeze(r,window())).isInstanceOf(Failure.class);assertThat(scalar(db,"SELECT count(*) FROM java_identity_deletion_operations")).isZero();
    });}
    @Test void actual_jdbc_native_provenance_is_transaction_scoped_and_cannot_hide_central_fences()throws Exception{isolated(false,db->{
        var owner=store(db);Request r=request();owner.central(ISSUER,r.identity().subject(),window());owner.freeze(r,window());
        DataSource actual=new NativeIdentityDataSource.ProvenanceDataSource(db);
        NativeIdentityOrigin.bind(new NativeIdentityOrigin.Origin(NativeIdentityOrigin.Kind.GATEWAY_KEY,"native-gateway",true));
        try(Connection c=actual.getConnection()) {
            c.setAutoCommit(false);
            try(var q=c.prepareStatement("INSERT INTO memory(user_id,fact) VALUES(?,'native profile fact')")) {
                q.setString(1,"actual-native-scope::profile::qa");q.executeUpdate();
            }
            c.commit();
            assertThat(scalar(db,"SELECT count(*) FROM java_identity_aliases WHERE storage_key='actual-native-scope' AND kind='native'")).isEqualTo(1);
            // Even the global native gateway cannot relabel a known central key.
            assertThatThrownBy(()->{try(var q=c.prepareStatement("INSERT INTO memory(user_id,fact) VALUES(?,'denied')")){q.setString(1,r.identity().subject());q.executeUpdate();}})
                .isInstanceOf(SQLException.class).hasMessageContaining("identity_deletion_fenced");
            c.rollback();NativeIdentityOrigin.clear();
            // Reuse the same physical connection after commit/rollback: no native
            // request origin survives to silently authorize another storage name.
            assertThatThrownBy(()->{try(var q=c.createStatement()){q.execute("INSERT INTO memory(user_id,fact) VALUES('unproved-after-native','denied')");}})
                .isInstanceOf(SQLException.class).hasMessageContaining("identity_reference_unknown");
            c.rollback();
        }
        assertThat(scalar(db,"SELECT count(*) FROM java_identity_aliases WHERE storage_key='"+r.identity().subject()+"' AND kind='central'")).isEqualTo(1);
    });}
    @Test void native_user_key_cannot_name_another_scope_and_savepoint_rollback_does_not_restore_proof()throws Exception{isolated(false,db->{
        store(db);DataSource actual=new NativeIdentityDataSource.ProvenanceDataSource(db);
        NativeIdentityOrigin.bind(new NativeIdentityOrigin.Origin(NativeIdentityOrigin.Kind.USER_API_KEY,"native-user",false));
        try(Connection c=actual.getConnection()) {
            c.setAutoCommit(false);
            try(var q=c.createStatement()){q.execute("INSERT INTO memory(user_id,fact) VALUES('native-user','permitted')");}
            var point=c.setSavepoint();NativeIdentityOrigin.clear();
            try(var q=c.createStatement()){q.execute("SELECT 1");}
            c.rollback(point);
            assertThatThrownBy(()->{try(var q=c.createStatement()){q.execute("INSERT INTO memory(user_id,fact) VALUES('different-native-user','denied')");}})
                .isInstanceOf(SQLException.class).hasMessageContaining("identity_reference_unknown");
            c.rollback();
            NativeIdentityOrigin.bind(new NativeIdentityOrigin.Origin(NativeIdentityOrigin.Kind.USER_API_KEY,"native-user",false));
            assertThatThrownBy(()->{try(var q=c.createStatement()){q.execute("INSERT INTO memory(user_id,fact) VALUES('not-own-key','denied')");}})
                .isInstanceOf(SQLException.class).hasMessageContaining("identity_reference_unknown");
            c.rollback();
        }
    });}
    private record Writer(String source,String insert) {}
    @Test void every_backend_reference_writer_counts_real_rows_and_rejects_inserts_and_updates_after_freeze()throws Exception{isolated(false,db->{
        var owner=store(db);Request r=request();String user=r.identity().subject(),sid=UUID.randomUUID().toString(),checkpoint=UUID.randomUUID().toString(),cron=UUID.randomUUID().toString();
        owner.central(ISSUER,user,window());
        List<Writer> writers=List.of(
            new Writer("sessions","INSERT INTO sessions(id,user_id,model_provider,model_name) VALUES('"+sid+"','"+user+"','noop','synthetic')"),
            new Writer("memory","INSERT INTO memory(user_id,fact) VALUES('"+user+"','synthetic')"),
            new Writer("memory_pending","INSERT INTO memory_pending(id,user_id,action) VALUES(gen_random_uuid(),'"+user+"','add')"),
            new Writer("todos","INSERT INTO todos(user_id,session_id,title) VALUES('"+user+"','"+sid+"','synthetic')"),
            new Writer("skills","INSERT INTO skills(name,content,user_id) VALUES('identity-fixture','synthetic','"+user+"')"),
            new Writer("cron_jobs","INSERT INTO cron_jobs(id,name,schedule,user_id) VALUES('"+cron+"','synthetic','0 0 * * *','"+user+"')"),
            new Writer("checkpoints","INSERT INTO checkpoints(id,user_id) VALUES('"+checkpoint+"','"+user+"')"),
            new Writer("cron_execution_log","INSERT INTO cron_execution_log(job_id,started_at,status,user_id) VALUES('"+cron+"',now(),'success','"+user+"')"),
            new Writer("usage_log","INSERT INTO usage_log(user_id,session_id) VALUES('"+user+"','"+sid+"')"),
            new Writer("audit_log","INSERT INTO audit_log(user_id,session_id,actor,action) VALUES('"+user+"','"+sid+"','"+user+"','synthetic')"),
            new Writer("skill_audit_log","INSERT INTO skill_audit_log(skill_name,action,user_id) VALUES('synthetic','create','"+user+"')"),
            new Writer("delivery_work_items","INSERT INTO delivery_work_items(id,source_type,source_id,profile,user_id,target_kind,target_hash,payload_text,payload_hash) VALUES(gen_random_uuid(),'synthetic','synthetic','default','"+user+"','local','synthetic','synthetic','synthetic')"),
            new Writer("gateway_home_channels","INSERT INTO gateway_home_channels(platform,profile,chat_id,user_id,updated_by,updated_at) VALUES('telegram','synthetic','1','"+user+"','"+user+"',now())"),
            new Writer("outbound_message_receipts","INSERT INTO outbound_message_receipts(id,platform,chat_id,user_id,message_id,idempotency_key,created_at) VALUES(gen_random_uuid(),'telegram','1','"+user+"','1','synthetic',now())"),
            new Writer("openai_run_states","INSERT INTO openai_run_states(run_id,session_id,user_id,state) VALUES('synthetic-run','"+sid+"','"+user+"','queued')"),
            new Writer("code_session_kernels","INSERT INTO code_session_kernels(kernel_id,session_id,user_id,expires_at) VALUES('synthetic','"+sid+"','"+user+"',now()+interval '1 hour')"),
            new Writer("console_tasks","INSERT INTO console_tasks(id,user_id,session_id,command) VALUES('synthetic-console','"+user+"','"+sid+"','synthetic')"),
            new Writer("memory_write_audit","INSERT INTO memory_write_audit(user_id,target,action) VALUES('"+user+"','memory','add')"),
            new Writer("attachment_artifacts","INSERT INTO attachment_artifacts(id,owner_id,content_hash,origin,disposition) VALUES('synthetic-artifact','"+user+"','synthetic','internal','file')"),
            new Writer("messages","INSERT INTO messages(session_id,role,content) VALUES('"+sid+"','user','synthetic')"),
            new Writer("session_cli_state","INSERT INTO session_cli_state(session_id,state_key,state_value) VALUES('"+sid+"','synthetic','synthetic')"),
            new Writer("compression_locks","INSERT INTO compression_locks(session_id) VALUES('"+sid+"')"),
            new Writer("background_jobs","INSERT INTO background_jobs(id,session_id) VALUES(gen_random_uuid(),'"+sid+"')"),
            new Writer("openai_responses","INSERT INTO openai_responses(response_id,response_json,conversation_history_json,session_id) VALUES('synthetic-response','{}','[]','"+sid+"')"),
            new Writer("delegated_task_runs","INSERT INTO delegated_task_runs(id,parent_session_id,goal) VALUES(gen_random_uuid(),'"+sid+"','synthetic')"),
            new Writer("pending_review_summaries","INSERT INTO pending_review_summaries(delivery_id,session_id,summary) VALUES(gen_random_uuid(),'"+sid+"','synthetic')"),
            new Writer("checkpoint_files","INSERT INTO checkpoint_files(checkpoint_id,file_path,file_hash) VALUES('"+checkpoint+"','synthetic','synthetic')"),
            new Writer("console_task_output","INSERT INTO console_task_output(task_id,sequence,line) VALUES('synthetic-console',1,'synthetic')"),
            new Writer("openai_response_conversations","INSERT INTO openai_response_conversations(name,response_id) VALUES('synthetic-conversation','synthetic-response')"),
            new Writer("openai_run_events","INSERT INTO openai_run_events(run_id,seq,event_json) VALUES('synthetic-run',1,'{}')"),
            new Writer("dashboard_actions","INSERT INTO dashboard_actions(action,actor) VALUES('synthetic','"+user+"')"),
            new Writer("profile_config_revisions","INSERT INTO profile_config_revisions(profile,revision,actor,summary_hash) VALUES('synthetic',1,'"+user+"','synthetic')"),
            new Writer("java_identity_aliases","INSERT INTO java_identity_aliases(storage_key,kind,issuer,subject,origin) VALUES('actual-retained-alias','central','"+ISSUER+"','"+user+"','operator_proven')"),
            new Writer("java_identity_session_aliases","INSERT INTO java_identity_session_aliases(session_id,storage_key) VALUES(gen_random_uuid(),'"+user+"')")
        );
        assertThat(writers).hasSize(34);
        for(Writer writer:writers) {
            sql(db,writer.insert());
            assertThat(scalar(db,"SELECT count(*) FROM "+writer.source()+" t CROSS JOIN LATERAL java_identity_row_refs('"+writer.source()+"',to_jsonb(t)) r WHERE r.issuer='"+ISSUER+"' AND r.subject='"+user+"'"))
                .as("actual reference row: %s",writer.source()).isPositive();
        }
        long count=owner.check(r,window()).referenceCount();assertThat(count).isGreaterThanOrEqualTo(34);
        owner.freeze(r,window());
        for(Writer writer:writers) {
            assertThatThrownBy(()->sql(db,writer.insert())).as("INSERT fenced: %s",writer.source())
                .isInstanceOf(SQLException.class).hasMessageContaining("identity_deletion_fenced");
            String field=writer.source().equals("java_identity_aliases")?"storage_key":writer.source().equals("java_identity_session_aliases")?"storage_key":writer.source().equals("sessions")?"user_id":null;
            if(field==null) {
                // A real row update invokes the same BEFORE ROW fence even if
                // the business payload remains unchanged.
                try(Connection c=db.getConnection();var q=c.prepareStatement("SELECT a.attname FROM pg_attribute a WHERE a.attrelid=?::regclass AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum LIMIT 1")) {
                    q.setString(1,writer.source());try(var cols=q.executeQuery()){assertThat(cols.next()).isTrue();field=cols.getString(1);}
                }
            }
            String column=field;
            assertThatThrownBy(()->sql(db,"UPDATE "+writer.source()+" SET "+column+"="+column))
                .as("UPDATE fenced: %s",writer.source()).isInstanceOf(SQLException.class);
        }
        // The three remaining backend tables are actual native authorities,
        // not Auth subjects merely because one storage id resembles a UUID.
        sql(db,"INSERT INTO agent_users(id,username,role) VALUES('native-fixture','native-fixture','user')");
        sql(db,"INSERT INTO user_api_keys(id,user_id,key_hash) VALUES(gen_random_uuid(),'native-fixture',repeat('0',64))");
        sql(db,"INSERT INTO bot_sessions(id,user_id,chat_id) VALUES(gen_random_uuid(),'123456','123456')");
        assertThat(owner.check(new Request(1,UUID.randomUUID(),r.identity(),r.inventoryRevision()),window()).referenceCount()).isEqualTo(count);
    });}
    @Test void freeze_drains_actual_uncommitted_writer_and_old_repeatable_read_snapshot_cannot_escape()throws Exception{isolated(false,db->{
        var owner=store(db);Request r=request();owner.central(ISSUER,r.identity().subject(),window());
        try(var workers=Executors.newVirtualThreadPerTaskExecutor();Connection writer=db.getConnection()) {
            writer.setAutoCommit(false);
            try(var q=writer.prepareStatement("INSERT INTO memory(user_id,fact) VALUES(?,'uncommitted actual writer')")){q.setString(1,r.identity().subject());q.executeUpdate();}
            var freezing=workers.submit(()->owner.freeze(r,window()));blockedBy(db,pid(writer));
            assertThatThrownBy(()->freezing.get(50,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            writer.commit();Freeze receipt=freezing.get(5,TimeUnit.SECONDS);assertThat(receipt.referenceCount()).isGreaterThanOrEqualTo(2);
            owner.release(r,window());
            try(Connection stale=db.getConnection()) {
                stale.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);stale.setAutoCommit(false);
                try(var q=stale.createStatement();var snapshot=q.executeQuery("SELECT count(*) FROM memory")){assertThat(snapshot.next()).isTrue();}
                Request second=new Request(1,UUID.randomUUID(),r.identity(),r.inventoryRevision());owner.freeze(second,window());
                assertThatThrownBy(()->{try(var q=stale.prepareStatement("INSERT INTO memory(user_id,fact) VALUES(?,'old snapshot must fail')")){q.setString(1,r.identity().subject());q.executeUpdate();}})
                    .isInstanceOfSatisfying(SQLException.class,error->assertThat(error.getSQLState()).isIn("40001","23514"));
                stale.rollback();
            }
        }
    });}
    @Test void original_authority_expiry_while_waiting_for_real_writer_rolls_back_without_fence()throws Exception{isolated(false,db->{
        var owner=store(db);Request r=request();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor();Connection writer=db.getConnection()) {
            writer.setAutoCommit(false);try(var q=writer.createStatement()){q.execute("SELECT pg_advisory_xact_lock_shared(714402216006::bigint)");}
            Window original=new Window(System.nanoTime()+Duration.ofSeconds(2).toNanos(),Instant.now().plusSeconds(2));
            var freezing=workers.submit(()->owner.freeze(r,original));blockedBy(db,pid(writer));
            while(original.current())Thread.sleep(10);
            writer.rollback();
            assertThatThrownBy(()->freezing.get(4,TimeUnit.SECONDS)).isInstanceOfSatisfying(java.util.concurrent.ExecutionException.class,
                error->assertThat(error.getCause()).isInstanceOfSatisfying(Failure.class,failure->assertThat(failure.status()).isEqualTo(401)));
            assertThat(scalar(db,"SELECT count(*) FROM java_identity_deletion_operations")).isZero();
        }
    });}
    @Test void actual_sql_lock_timeout_is_bounded_and_never_reports_a_fence()throws Exception{isolated(false,db->{
        var owner=store(db);Request r=request();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor();Connection writer=db.getConnection()) {
            writer.setAutoCommit(false);try(var q=writer.createStatement()){q.execute("SELECT pg_advisory_xact_lock_shared(714402216006::bigint)");}
            long started=System.nanoTime();var freezing=workers.submit(()->owner.freeze(r,window()));blockedBy(db,pid(writer));
            assertThatThrownBy(()->freezing.get(11,TimeUnit.SECONDS)).isInstanceOfSatisfying(java.util.concurrent.ExecutionException.class,
                error->assertThat(error.getCause()).isInstanceOfSatisfying(Failure.class,failure->assertThat(failure.status()).isEqualTo(503)));
            long elapsed=Duration.ofNanos(System.nanoTime()-started).toMillis();assertThat(elapsed).isBetween(7500L,11000L);
            writer.rollback();assertThat(scalar(db,"SELECT count(*) FROM java_identity_deletion_operations")).isZero();
        }
    });}
    @Test void freeze_and_release_roll_back_when_the_original_window_expires_inside_commit()throws Exception{isolated(false,db->{
        var owner=store(db);
        deferredCommitExpiry(db,owner,"freeze");
        deferredCommitExpiry(db,owner,"release");
    });}
    @Test void central_registration_preserves_its_original_human_window_inside_commit()throws Exception{isolated(false,db->{
        deferredCommitExpiry(db,store(db),"central");
    });}
    private static void deferredCommitExpiry(DataSource db,IdentityDeletionStore owner,String action)throws Exception {
        Request request=request();
        Freeze before=action.equals("release")?owner.freeze(request,window()):null;
        String snapshotSql="SELECT jsonb_build_object('journal',(SELECT COALESCE(jsonb_agg(to_jsonb(o)),'[]'::jsonb) FROM java_identity_deletion_operations o WHERE operation_id='"+request.operationId()+"'),'epoch',(SELECT to_jsonb(e) FROM java_identity_fence_epoch e WHERE singleton),'alias',(SELECT COALESCE(jsonb_agg(to_jsonb(a)),'[]'::jsonb) FROM java_identity_aliases a WHERE storage_key='"+request.identity().subject()+"'))::text";
        String snapshot=text(db,snapshotSql);
        sql(db,"CREATE SEQUENCE qa_owner_commit_until; CREATE SEQUENCE qa_owner_commit_pid; CREATE SEQUENCE qa_owner_commit_valid; "+
            "CREATE FUNCTION qa_owner_commit_delay() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "+
            "PERFORM setval('qa_owner_commit_until',floor(extract(epoch FROM NEW.valid_until)*1000000)::bigint,true); "+
            "PERFORM setval('qa_owner_commit_pid',pg_backend_pid()::bigint,true); "+
            "PERFORM setval('qa_owner_commit_valid',CASE WHEN clock_timestamp()<NEW.valid_until THEN 1 ELSE 2 END,true); "+
            "PERFORM pg_advisory_xact_lock(714402216106::bigint); RETURN NEW; END $$; "+
            "CREATE CONSTRAINT TRIGGER a_qa_owner_commit_delay AFTER INSERT ON java_identity_request_commit_fences "+
            "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION qa_owner_commit_delay()");
        try(var workers=Executors.newVirtualThreadPerTaskExecutor();Connection barrier=db.getConnection()) {
            barrier.setAutoCommit(false);
            try(var q=barrier.createStatement()){q.execute("SELECT pg_advisory_xact_lock(714402216106::bigint)");}
            int blocker=pid(barrier);
            Window original=new Window(System.nanoTime()+Duration.ofSeconds(3).toNanos(),Instant.now().plusSeconds(30));
            var committing=workers.submit(()->{
                switch(action) {
                    case "freeze" -> owner.freeze(request,original);
                    case "release" -> owner.release(request,original);
                    case "central" -> owner.central(ISSUER,request.identity().subject(),original);
                    default -> throw new AssertionError("unknown fixture action");
                }
            });
            try {
                long barrierDeadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
                while(scalar(db,"SELECT count(*) FROM qa_owner_commit_pid WHERE is_called AND "+blocker+"=ANY(pg_blocking_pids(last_value::int))")==0) {
                    assertThat(System.nanoTime()).as("exact owner backend must enter deferred COMMIT").isLessThan(barrierDeadline);
                    Thread.sleep(10);
                }
                assertThat(scalar(db,"SELECT last_value FROM qa_owner_commit_valid")).as("actual DB deadline is current at deferred COMMIT entry").isEqualTo(1);
                assertThat(scalar(db,"SELECT count(*) FROM qa_owner_commit_until WHERE clock_timestamp()<to_timestamp(last_value::double precision/1000000)"))
                    .as("actual blocked COMMIT must still have an unexpired original cap").isEqualTo(1);
                long expiryDeadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
                while(scalar(db,"SELECT count(*) FROM qa_owner_commit_until WHERE clock_timestamp()>=to_timestamp(last_value::double precision/1000000)")==0) {
                    assertThat(System.nanoTime()).as("the fixed DB cap expires without renewal").isLessThan(expiryDeadline);
                    Thread.sleep(10);
                }
                barrier.commit();
                assertThatThrownBy(()->committing.get(4,TimeUnit.SECONDS)).isInstanceOfSatisfying(java.util.concurrent.ExecutionException.class,
                    error->assertThat(error.getCause()).isInstanceOfSatisfying(Failure.class,failure->{
                        assertThat(failure.status()).isEqualTo(401);assertThat(failure.code()).isEqualTo("identity_authority_expired");
                    }));
            } finally {
                barrier.rollback();
                committing.cancel(true);
            }
        } finally {
            sql(db,"DROP TRIGGER a_qa_owner_commit_delay ON java_identity_request_commit_fences; DROP FUNCTION qa_owner_commit_delay(); "+
                "DROP SEQUENCE qa_owner_commit_until; DROP SEQUENCE qa_owner_commit_pid; DROP SEQUENCE qa_owner_commit_valid;");
        }
        assertThat(text(db,snapshotSql)).as("journal, epoch and provenance all roll back exactly").isEqualTo(snapshot);
        assertThat(scalar(db,"SELECT count(*) FROM java_identity_request_commit_fences")).isZero();
        if(action.equals("central")) {
            owner.central(ISSUER,request.identity().subject(),window());
            assertThat(owner.check(request,window()).referenceCount()).isPositive();
        } else {
            Freeze fresh=owner.freeze(request,window());
            if(before!=null)assertThat(fresh).as("expired release retains the exact original freeze receipt").isEqualTo(before);
            owner.release(request,window());
        }
    }
    @Test void commit_clock_diagnostic_is_exact_and_other_permission_failures_remain_unavailable()throws Exception{isolated(false,db->{
        var owner=store(db);
        assertThatThrownBy(()->sql(db,"INSERT INTO java_identity_request_commit_fences(request_id,valid_until) VALUES(gen_random_uuid(),clock_timestamp()-interval '1 second')"))
            .isInstanceOfSatisfying(SQLException.class,error->assertThat(error.getSQLState()).isEqualTo("42501"));
        for(String diagnostic:new String[]{"RAISE EXCEPTION 'other_permission_failure' USING ERRCODE='42501',CONSTRAINT='java_identity_request_commit_clock'",
                "RAISE EXCEPTION 'java_identity_request_authority_expired' USING ERRCODE='42501',CONSTRAINT='other_permission_constraint'"}) {
            sql(db,"CREATE FUNCTION qa_other_permission() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "+diagnostic+"; END $$; "+
                "CREATE CONSTRAINT TRIGGER a_qa_other_permission AFTER INSERT ON java_identity_request_commit_fences "+
                "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION qa_other_permission()");
            try {
                Request request=request();
                assertThatThrownBy(()->owner.freeze(request,window())).isInstanceOfSatisfying(Failure.class,
                    failure->{assertThat(failure.status()).isEqualTo(503);assertThat(failure.code()).isEqualTo("identity_deletion_storage_unavailable");});
                assertThat(scalar(db,"SELECT count(*) FROM java_identity_deletion_operations WHERE operation_id='"+request.operationId()+"'")).isZero();
            } finally {sql(db,"DROP TRIGGER a_qa_other_permission ON java_identity_request_commit_fences; DROP FUNCTION qa_other_permission()");}
        }
    });}
    private static java.net.http.HttpResponse<String> post(IdentityOwnerHttpFixture server,Request request,String action,String token,String payload)throws Exception {
        var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create(server.baseUrl+"/api/internal/v1/user-deletions/"+request.operationId()+"/"+action))
            .timeout(Duration.ofSeconds(13)).header("Content-Type","application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload==null?new ObjectMapper().writeValueAsString(request):payload));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        var response=java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build().send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");return response;
    }
    @Test void actual_private_servlet_http_requires_fresh_registered_machine_capability_and_bounds_all_io()throws Exception{isolated(false,db->{
        var owner=store(db);
        try(var server=new IdentityOwnerHttpFixture(owner)) {
            Request r=request();
            assertThat(post(server,r,"check",null,null).statusCode()).isEqualTo(401);
            assertThat(post(server,r,"check","public-synthetic-native-key",null).statusCode()).isEqualTo(403);
            assertThat(post(server,r,"check","eyJhbGciOiJFUzI1NiJ9.public.synthetic",null).statusCode()).isEqualTo(403);
            assertThat(server.authCalls.get()).isZero();
            server.role.set("admin");assertThat(post(server,r,"check",IdentityOwnerHttpFixture.PAT,null).statusCode()).isEqualTo(403);
            server.role.set("service_account");server.scopes.set(List.of("java-agent:read"));
            assertThat(post(server,r,"check",IdentityOwnerHttpFixture.PAT,null).statusCode()).isEqualTo(403);
            server.scopes.set(List.of("java-agent:identity-lifecycle"));server.subject.set(UUID.randomUUID().toString());
            assertThat(post(server,r,"check",IdentityOwnerHttpFixture.PAT,null).statusCode()).isEqualTo(403);
            server.subject.set(server.coordinator.toString());server.status.set(401);
            assertThat(post(server,r,"check",IdentityOwnerHttpFixture.PAT,null).statusCode()).isEqualTo(401);
            server.status.set(200);
            var confirmed=post(server,r,"check",IdentityOwnerHttpFixture.PAT,null);assertThat(confirmed.statusCode()).isEqualTo(200);
            assertThat(new ObjectMapper().readTree(confirmed.body()).path("reference_count").asLong()).isZero();
            assertThat(post(server,r,"freeze",IdentityOwnerHttpFixture.PAT,null).statusCode()).isEqualTo(200);
            assertThat(post(server,r,"release",IdentityOwnerHttpFixture.PAT,null).statusCode()).isEqualTo(200);
            assertThat(post(server,r,"freeze",IdentityOwnerHttpFixture.PAT,null).statusCode()).isEqualTo(409);
            int calls=server.authCalls.get();
            assertThat(post(server,request(),"check",IdentityOwnerHttpFixture.PAT,"x".repeat(16385)).statusCode()).isEqualTo(413);
            assertThat(post(server,request(),"check",IdentityOwnerHttpFixture.PAT,"{\"schema_version\":null}").statusCode()).isEqualTo(400);
            assertThat(server.authCalls.get()).isEqualTo(calls);
            server.status.set(401);assertThat(post(server,request(),"check",IdentityOwnerHttpFixture.PAT,null).statusCode()).isEqualTo(401);server.status.set(200);
            try(var workers=Executors.newVirtualThreadPerTaskExecutor();Connection blocker=db.getConnection()) {
                blocker.setAutoCommit(false);try(var q=blocker.createStatement()){q.execute("SELECT pg_advisory_xact_lock_shared(714402216006::bigint)");}
                Request expiring=request();Instant expires=Instant.now().plusSeconds(2);server.expiry.set(expires);
                var waiting=workers.submit(()->post(server,expiring,"freeze",IdentityOwnerHttpFixture.PAT,null));blockedBy(db,pid(blocker));
                while(Instant.now().isBefore(expires))Thread.sleep(10);blocker.rollback();
                assertThat(waiting.get(5,TimeUnit.SECONDS).statusCode()).isEqualTo(401);
                assertThat(scalar(db,"SELECT count(*) FROM java_identity_deletion_operations WHERE operation_id='"+expiring.operationId()+"'")).isZero();
                server.expiry.set(null);server.authDelayMillis.set(2500);
                try(var q=blocker.createStatement()){q.execute("SELECT pg_advisory_xact_lock_shared(714402216006::bigint)");}
                Request timed=request();long started=System.nanoTime();
                var wholeRequest=workers.submit(()->post(server,timed,"freeze",IdentityOwnerHttpFixture.PAT,null));blockedBy(db,pid(blocker));
                var timedResponse=wholeRequest.get(12,TimeUnit.SECONDS);assertThat(timedResponse.statusCode()).isEqualTo(503);
                assertThat(Duration.ofNanos(System.nanoTime()-started).toMillis()).isBetween(8500L,11500L);
                blocker.rollback();
                for(int attempt=0;attempt<200 && server.activeStoreCalls.get()!=0;attempt++)Thread.sleep(10);
                assertThat(server.activeStoreCalls.get()).isZero();
                assertThat(scalar(db,"SELECT count(*) FROM java_identity_deletion_operations WHERE operation_id='"+timed.operationId()+"'")).isZero();
            }
            // Private authorization never registers a coordinator as a product
            // content identity or provisions any native account.
            assertThat(scalar(db,"SELECT count(*) FROM java_identity_aliases WHERE kind='central'")).isZero();
            assertThat(scalar(db,"SELECT count(*) FROM agent_users")).isZero();
        }
    });}
}
