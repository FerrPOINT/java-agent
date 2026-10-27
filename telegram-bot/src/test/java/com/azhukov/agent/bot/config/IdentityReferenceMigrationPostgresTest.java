package com.azhukov.agent.bot.config;

import com.azhukov.agent.core.model.IdentityDeletionProtocol.*;
import com.azhukov.agent.persistence.service.IdentityDeletionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

/** Both real Flyway histories must converge before bot writers can escape an acknowledged fence. */
@Tag("slow")
class IdentityReferenceMigrationPostgresTest {
    private static final String ISSUER="https://auth.example.test";
    private static Window window(){return new Window(System.nanoTime()+Duration.ofSeconds(30).toNanos(),Instant.now().plusSeconds(30));}
    private static void sql(DataSource db,String text)throws SQLException{try(Connection c=db.getConnection();var q=c.createStatement()){q.execute(text);}}
    private static long scalar(DataSource db,String text)throws SQLException{try(Connection c=db.getConnection();var q=c.createStatement();var r=q.executeQuery(text)){r.next();return r.getLong(1);}}
    private static void backend(DataSource db){Flyway.configure().dataSource(db).locations("classpath:db/migration","classpath:db/postgresql").baselineOnMigrate(true).outOfOrder(true).load().migrate();}
    private static void bot(DataSource db){Flyway.configure().dataSource(db).locations("classpath:db/bot-migration").table("flyway_bot_schema_history")
        .baselineOnMigrate(true).baselineVersion("0").callbacks(new IdentityReferenceMigrationCallback()).load().migrate();}

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void actual_bot_and_backend_migration_orders_preserve_native_kind_and_fence_both_real_bot_reference_tables(boolean backendFirst)throws Exception {
        String url=System.getenv("JAVA_IDENTITY_TEST_JDBC_URL");assertThat(url).as("dedicated JAVA_IDENTITY_TEST_JDBC_URL required").isNotBlank();
        var base=new DriverManagerDataSource(url);String schema="identity_bot_"+UUID.randomUUID().toString().replace("-","");
        assertThat(scalar(base,"SELECT CASE WHEN current_database()='java_identity_test' THEN 1 ELSE 0 END")).isEqualTo(1);
        sql(base,"CREATE SCHEMA "+schema);
        DataSource db=new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException{Connection c=base.getConnection();try(var q=c.createStatement()){q.execute("SET search_path TO "+schema);}return c;}
            @Override public Connection getConnection(String user,String password)throws SQLException{return getConnection();}
        };
        try {
            if(backendFirst){backend(db);bot(db);}else{
                bot(db);sql(db,"INSERT INTO bot_sessions(id,user_id,chat_id) VALUES(gen_random_uuid(),'legacy-native-telegram','legacy-native-chat')");backend(db);
            }
            var environment=new MockEnvironment().withProperty("agent.central-auth.issuer",ISSUER).withProperty("agent.identity-deletion.legacy-issuer",ISSUER);
            var owner=new IdentityDeletionStore(db,environment,new ObjectMapper());ReflectionTestUtils.invokeMethod(owner,"init");
            Request request=new Request(1,UUID.randomUUID(),new Identity(ISSUER,UUID.randomUUID().toString()),UUID.randomUUID());
            assertThat(owner.check(request,window()).referenceCount()).isZero();
            assertThat(scalar(db,"SELECT count(*) FROM pg_trigger t WHERE t.tgrelid IN ('bot_messages'::regclass,'review_delivery_receipts'::regclass) AND t.tgname IN ('a_identity_reference_lock','b_identity_native_origin','z_identity_reference_guard') AND t.tgenabled IN ('O','A')")).isEqualTo(6);
            owner.central(ISSUER,request.identity().subject(),window());
            UUID backendSession=UUID.randomUUID(),botSession=UUID.randomUUID();
            sql(db,"INSERT INTO sessions(id,user_id,model_provider,model_name) VALUES('"+backendSession+"','"+request.identity().subject()+"','noop','synthetic')");
            sql(db,"INSERT INTO bot_sessions(id,user_id,chat_id,backend_session_id) VALUES('"+botSession+"','123456','123456','"+backendSession+"')");
            String message="INSERT INTO bot_messages(session_id,role,content) VALUES('"+botSession+"','user','synthetic')";
            String receipt="INSERT INTO review_delivery_receipts(id,backend_session_id,backend_delivery_id) VALUES(gen_random_uuid(),'"+backendSession+"',gen_random_uuid())";
            sql(db,message);sql(db,receipt);
            assertThat(scalar(db,"SELECT count(*) FROM bot_messages m CROSS JOIN LATERAL java_identity_row_refs('bot_messages',to_jsonb(m)) r WHERE r.subject='"+request.identity().subject()+"'")).isEqualTo(1);
            assertThat(scalar(db,"SELECT count(*) FROM review_delivery_receipts m CROSS JOIN LATERAL java_identity_row_refs('review_delivery_receipts',to_jsonb(m)) r WHERE r.subject='"+request.identity().subject()+"'")).isEqualTo(1);
            owner.freeze(request,window());
            assertThatThrownBy(()->sql(db,message)).isInstanceOf(SQLException.class).hasMessageContaining("identity_deletion_fenced");
            assertThatThrownBy(()->sql(db,receipt)).isInstanceOf(SQLException.class).hasMessageContaining("identity_deletion_fenced");
            assertThatThrownBy(()->sql(db,"UPDATE bot_messages SET content='denied'")).isInstanceOf(SQLException.class).hasMessageContaining("identity_deletion_fenced");
            assertThatThrownBy(()->sql(db,"UPDATE review_delivery_receipts SET delivered_at=now()")).isInstanceOf(SQLException.class).hasMessageContaining("identity_deletion_fenced");
        } finally {try(Connection c=base.getConnection();var q=c.createStatement()){q.execute("SET statement_timeout='15s'");q.execute("DROP SCHEMA "+schema+" CASCADE");}}
    }
}
