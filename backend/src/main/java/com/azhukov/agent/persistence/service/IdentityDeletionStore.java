package com.azhukov.agent.persistence.service;

import com.azhukov.agent.core.model.IdentityDeletionProtocol.*;
import com.azhukov.agent.core.ports.IdentityDeletionOwner;
import com.azhukov.agent.core.ports.IdentityReferenceRegistration;
import com.azhukov.agent.core.security.NativeIdentityOrigin;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.postgresql.util.PSQLException;

/**
 * PostgreSQL-owned reference barrier. It retains proven native/central origins
 * and never treats the shape of a stored user id as central identity evidence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@DependsOn("flyway")
@ConditionalOnProperty(prefix="agent.identity-deletion", name="enabled", havingValue="true")
public class IdentityDeletionStore implements IdentityDeletionOwner, IdentityReferenceRegistration {
    private final DataSource dataSource;
    private final Environment environment;
    private final ObjectMapper mapper;
    private static final String OWNER="java-agent";
    private static final String LOCK="SELECT pg_advisory_xact_lock(714402216006::bigint)";

    @PostConstruct
    void init() {
        String issuer=environment.getProperty("agent.identity-deletion.legacy-issuer", "");
        if (!issuer.isBlank()) {
            if (!issuer.equals(environment.getProperty("agent.central-auth.issuer"))) throw unavailable();
            transaction(localWindow(),true,c->{
                String bound=boundIssuer(c);
                if(bound!=null && !issuer.equals(bound)) throw conflict();
                if(bound==null) execute(c,"INSERT INTO java_identity_issuer(issuer) VALUES(?)",issuer);
                return null;
            });
        }
    }

    @Override
    public Summary check(Request request,Window window) {
        return transaction(window,true,c->{
            begin(c,request);readOperation(c,request);
            long count=count(c,request,window);long revision=revision(c);
            return new Summary(request,OWNER,revision,count,true);
        });
    }
    @Override
    public Freeze freeze(Request request,Window window) {
        return transaction(window,true,c->{
            begin(c,request);Saved saved=readOperation(c,request);
            if(saved!=null) {
                if(!"frozen".equals(saved.state())) throw conflict();
                return new Freeze(request,OWNER,saved.fence(),saved.revision(),saved.count());
            }
            try(PreparedStatement q=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM java_identity_deletion_operations WHERE issuer=? AND subject=? AND state='frozen')")) {
                q.setString(1,request.identity().issuer());q.setString(2,request.identity().subject());
                try(ResultSet r=q.executeQuery()){r.next();if(r.getBoolean(1)) throw conflict();}
            }
            long count=count(c,request,window),revision=revision(c);UUID fence=UUID.randomUUID();
            execute(c,"INSERT INTO java_identity_deletion_operations(operation_id,issuer,subject,inventory_revision,request,state,fence,revision,reference_count) VALUES(?,?,?,?,?::jsonb,'frozen',?,?,?)",
                request.operationId(),request.identity().issuer(),request.identity().subject(),request.inventoryRevision(),json(request),fence,revision,count);
            return new Freeze(request,OWNER,fence,revision,count);
        });
    }
    @Override
    public Release release(Request request,Window window) {
        return transaction(window,true,c->{
            begin(c,request);readOperation(c,request);long revision=revision(c);
            execute(c,"INSERT INTO java_identity_deletion_operations(operation_id,issuer,subject,inventory_revision,request,state,fence,revision,reference_count) VALUES(?,?,?,?,?::jsonb,'released',NULL,?,0) ON CONFLICT(operation_id) DO UPDATE SET state='released',fence=NULL",
                request.operationId(),request.identity().issuer(),request.identity().subject(),request.inventoryRevision(),json(request),revision);
            return new Release(request,OWNER,true);
        });
    }
    @Override
    public void central(String issuer,String subject,Window window) {
        Request probe=new Request(1,UUID.randomUUID(),new Identity(issuer,subject),UUID.randomUUID());
        if(!probe.valid() || !issuer.equals(environment.getProperty("agent.central-auth.issuer"))) throw new Failure(401,"identity_binding_invalid");
        transaction(window,false,c->{
            execute(c,"INSERT INTO java_identity_aliases(storage_key,kind,issuer,subject,origin) VALUES(?,'central',?,?,'verified_central_session') ON CONFLICT DO NOTHING",subject,issuer,subject);
            try(PreparedStatement q=c.prepareStatement("SELECT kind,issuer,subject FROM java_identity_aliases WHERE storage_key=?")) {
                q.setString(1,subject);try(ResultSet r=q.executeQuery()) {
                    if(!r.next() || !"central".equals(r.getString(1)) || !issuer.equals(r.getString(2)) || !subject.equals(r.getString(3))) throw conflict();
                }
            }
            return null;
        });
    }
    @Override
    public void nativeStorage(String storageKey) {
        if(storageKey==null || storageKey.isBlank() || storageKey.length()>1024) throw unknown();
        NativeIdentityOrigin.Origin origin=NativeIdentityOrigin.current();
        transaction(localWindow(),false,c->{
            try(PreparedStatement q=c.prepareStatement("SELECT kind FROM java_identity_aliases WHERE storage_key=?")) {
                q.setString(1,storageKey);try(ResultSet r=q.executeQuery()) {
                    if(r.next()) {
                        // A gateway may name an already central-owned storage
                        // key, but cannot reattribute it. The real write still
                        // encounters its central reference fence.
                        return null;
                    }
                }
            }
            if(origin==null || !origin.permits(storageKey)) throw unknown();
            execute(c,"INSERT INTO java_identity_aliases(storage_key,kind,origin) VALUES(?,'native',?) ON CONFLICT DO NOTHING",storageKey,"verified_native_"+origin.kind().name().toLowerCase(java.util.Locale.ROOT));
            try(PreparedStatement q=c.prepareStatement("SELECT kind FROM java_identity_aliases WHERE storage_key=?")) {
                q.setString(1,storageKey);try(ResultSet r=q.executeQuery()) { if(!r.next() || !"native".equals(r.getString(1))) throw conflict(); }
            }
            return null;
        });
    }
    private void begin(Connection c,Request request) throws SQLException {
        if(!request.valid()) throw new Failure(400,"invalid_identity_deletion_request");
        if(!request.identity().issuer().equals(boundIssuer(c))) throw unknown();
    }
    private String boundIssuer(Connection c) throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT issuer FROM java_identity_issuer WHERE singleton");ResultSet r=q.executeQuery()) {return r.next()?r.getString(1):null;}
    }
    private record Saved(String state,UUID fence,long revision,long count) {}
    private Saved readOperation(Connection c,Request request) throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT request::text,state,fence,revision,reference_count FROM java_identity_deletion_operations WHERE operation_id=?")) {
            q.setObject(1,request.operationId());try(ResultSet r=q.executeQuery()) {
                if(!r.next()) return null;
                try { if(!mapper.readTree(r.getString(1)).equals(mapper.valueToTree(request))) throw conflict(); }
                catch(java.io.IOException invalid){throw unavailable();}
                return new Saved(r.getString(2),r.getObject(3,UUID.class),r.getLong(4),r.getLong(5));
            }
        }
    }
    private long count(Connection c,Request request,Window window) throws SQLException {
        List<String> sources=new ArrayList<>();
        try(PreparedStatement q=c.prepareStatement("SELECT source,required,to_regclass(source) IS NOT NULL AS present,EXISTS(SELECT 1 FROM pg_trigger t WHERE t.tgrelid=to_regclass(source) AND t.tgname='a_identity_reference_lock' AND t.tgtype=30 AND t.tgqual IS NULL AND t.tgfoid='java_identity_write_lock()'::regprocedure AND t.tgenabled IN ('O','A')) AS statement_guard,EXISTS(SELECT 1 FROM pg_trigger t WHERE t.tgrelid=to_regclass(source) AND t.tgname='z_identity_reference_guard' AND t.tgtype=23 AND t.tgqual IS NULL AND t.tgfoid='java_identity_reference_guard()'::regprocedure AND t.tgenabled IN ('O','A')) AS row_guard,EXISTS(SELECT 1 FROM pg_trigger t WHERE t.tgrelid=to_regclass(source) AND t.tgname='b_identity_native_origin' AND t.tgtype=23 AND t.tgqual IS NULL AND t.tgfoid='java_identity_capture_native_origin()'::regprocedure AND t.tgenabled IN ('O','A')) AS native_guard FROM java_identity_reference_sources ORDER BY source");ResultSet r=q.executeQuery()) {
            while(r.next()) {
                if(!r.getBoolean("present")) {if(r.getBoolean("required"))throw unknown();continue;}
                if(!r.getBoolean("statement_guard") || !r.getBoolean("row_guard") || !r.getBoolean("native_guard"))throw unknown();
                sources.add(r.getString(1));
            }
        }
        if(sources.isEmpty()) throw unknown();
        long total=0;
        for(String source:sources) {
            window.requireCurrent();if(!source.matches("[a-z_]+")) throw unknown();
            try(PreparedStatement q=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM "+source+" t CROSS JOIN LATERAL java_identity_row_refs('"+source+"',to_jsonb(t)) r WHERE r.resolved IS NOT TRUE OR NULLIF(btrim(r.issuer),'') IS NULL OR NULLIF(btrim(r.subject),'') IS NULL)");ResultSet r=q.executeQuery()) {r.next();if(r.getBoolean(1)) throw unknown();}
            try(PreparedStatement q=c.prepareStatement("SELECT count(*) FROM "+source+" t WHERE EXISTS(SELECT 1 FROM java_identity_row_refs('"+source+"',to_jsonb(t)) r WHERE r.issuer=? AND r.subject=?)")) {
                q.setString(1,request.identity().issuer());q.setString(2,request.identity().subject());
                try(ResultSet r=q.executeQuery()){r.next();total=Math.addExact(total,r.getLong(1));}
            }
        }
        return total;
    }
    private long revision(Connection c) throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT nextval('java_identity_reference_revision')");ResultSet r=q.executeQuery()){r.next();return r.getLong(1);}
    }
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(java.io.IOException invalid){throw unavailable();}}
    private static Window localWindow(){return new Window(System.nanoTime()+Duration.ofSeconds(8).toNanos(),Instant.now().plusSeconds(8));}
    private static Failure unavailable(){return new Failure(503,"identity_deletion_storage_unavailable");}
    private static Failure unknown(){return new Failure(503,"identity_binding_unknown");}
    private static Failure conflict(){return new Failure(409,"identity_deletion_operation_conflict");}
    private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T transaction(Window window,boolean exclusive,Work<T> work) {
        window.requireCurrent();
        try(Connection c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                execute(c,"SET LOCAL lock_timeout='8s'");execute(c,"SET LOCAL statement_timeout='8s'");
                bindCommitClock(c,window);
                execute(c,exclusive?LOCK:"SELECT pg_advisory_xact_lock_shared(714402216006::bigint)");
                if(exclusive){if(execute(c,"UPDATE java_identity_fence_epoch SET revision=revision+1 WHERE singleton")!=1) throw unknown();}
                else {try(PreparedStatement q=c.prepareStatement("SELECT singleton FROM java_identity_fence_epoch WHERE singleton FOR SHARE");ResultSet r=q.executeQuery()){if(!r.next())throw unknown();}}
                window.requireCurrent();T result=work.run(c);window.requireCurrent();c.commit();return result;
            } catch(RuntimeException|SQLException error) {
                try{c.rollback();}catch(SQLException ignored){ /* Connection close also retires a failed transaction. */ }
                if(error instanceof Failure failure) throw failure;
                if(error instanceof PSQLException sql && "42501".equals(sql.getSQLState())
                    && sql.getServerErrorMessage()!=null
                    && "java_identity_request_authority_expired".equals(sql.getServerErrorMessage().getMessage())
                    && "java_identity_request_commit_clock".equals(sql.getServerErrorMessage().getConstraint()))
                    throw new Failure(401,"identity_authority_expired");
                if(error instanceof SQLException sql && "23514".equals(sql.getSQLState())) throw new Failure(403,"identity_reference_rejected");
                throw unavailable();
            }
        } catch(SQLException error){throw unavailable();}
    }
    private static void bindCommitClock(Connection c,Window window)throws SQLException {
        Instant observedAt;
        try(PreparedStatement q=c.prepareStatement("SELECT clock_timestamp()");ResultSet result=q.executeQuery()) {
            if(!result.next())throw unavailable();
            observedAt=result.getObject(1,OffsetDateTime.class).toInstant();
        }
        // This is measured after the DB response and before owner/reference locks.
        Instant until=window.commitExpiresAt(observedAt);
        UUID requestId=UUID.randomUUID();
        execute(c,"INSERT INTO java_identity_request_commit_fences(request_id,valid_until) VALUES(?,?)",
            requestId,until.atOffset(ZoneOffset.UTC));
        execute(c,"DELETE FROM java_identity_request_commit_fences WHERE request_id=?",requestId);
    }
    private static int execute(Connection c,String sql,Object... values)throws SQLException {
        try(PreparedStatement q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);boolean result=q.execute();return result?0:q.getUpdateCount();}
    }
}
