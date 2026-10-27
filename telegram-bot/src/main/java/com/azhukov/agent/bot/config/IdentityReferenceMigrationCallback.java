package com.azhukov.agent.bot.config;

import java.sql.Connection;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

/** Keeps optional bot reference tables inside the backend owner's migration barrier. */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdentityReferenceMigrationCallback implements Callback {
    @Override public boolean supports(Event event,Context context) {
        return event==Event.BEFORE_EACH_MIGRATE || event==Event.AFTER_EACH_MIGRATE;
    }
    @Override public boolean canHandleInTransaction(Event event,Context context){return true;}
    @Override public String getCallbackName(){return "identity-reference-owner";}
    @Override public void handle(Event event,Context context) {
        Connection connection=context.getConnection();
        try {
            if(!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))return;
            boolean installed;
            try(var q=connection.prepareStatement("SELECT to_regprocedure('java_identity_install_guards()') IS NOT NULL");var r=q.executeQuery()){r.next();installed=r.getBoolean(1);}
            if(!installed)return;
            try(var q=connection.createStatement()) {
                q.execute("SET LOCAL lock_timeout='8s';SET LOCAL statement_timeout='8s'");
                q.execute("SELECT pg_advisory_xact_lock(714402216006::bigint)");
                if(q.executeUpdate("UPDATE java_identity_fence_epoch SET revision=revision+1 WHERE singleton")!=1)throw new IllegalStateException("identity_fence_epoch_missing");
                if(event==Event.AFTER_EACH_MIGRATE)q.execute("SELECT java_identity_install_guards()");
            }
        } catch(SQLException failure){throw new IllegalStateException("identity_owner_migration_unavailable");}
    }
}
