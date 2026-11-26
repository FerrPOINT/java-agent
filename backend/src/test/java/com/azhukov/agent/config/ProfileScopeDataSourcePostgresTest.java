package com.azhukov.agent.config;

import com.azhukov.agent.core.security.ProfileDatabaseScope;
import com.azhukov.agent.persistence.PostgresTestContainer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
class ProfileScopeDataSourcePostgresTest extends PostgresTestContainer {

    @Autowired
    private DataSource migrationSource;

    @Test
    void theSamePooledConnectionCarriesOneProfileAndClearsItBeforeUnscopedUse() throws SQLException {
        String role = "java_history_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = UUID.randomUUID().toString();
        UUID alpha = UUID.randomUUID();
        UUID beta = UUID.randomUUID();
        try (var owner = migrationSource.getConnection(); var sql = owner.createStatement()) {
            sql.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "' NOSUPERUSER NOBYPASSRLS NOINHERIT");
            try {
                sql.execute("GRANT USAGE ON SCHEMA public TO " + role);
                sql.execute("GRANT SELECT ON sessions,messages TO " + role);
                sql.execute("INSERT INTO sessions(id, user_id, profile, model_provider, model_name) VALUES "
                    + "('" + alpha + "','fixture-history','alpha','noop','fixture'),('" + beta + "','fixture-history','beta','noop','fixture')");
                sql.execute("INSERT INTO messages(session_id,role,content) VALUES "
                    + "('" + alpha + "','user','alpha-private-history'),('" + beta + "','user','beta-private-history')");
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(owner.getMetaData().getURL());
                config.setUsername(role);
                config.setPassword(password);
                config.setMaximumPoolSize(1);

                try (var pool = new HikariDataSource(config)) {
                    var source = (DataSource) new ProfileScopeDataSource().postProcessAfterInitialization(pool, "profileFixture");
                    int backend;
                    try (var scope = ProfileDatabaseScope.enter("alpha"); var connection = source.getConnection()) {
                        connection.setAutoCommit(false);
                        backend = backend(connection);
                        assertThat(payloads(connection)).containsExactly("alpha-private-history");
                    }
                    try (var unbound = pool.getConnection()) {
                        assertThat(backend(unbound)).isEqualTo(backend);
                        assertThat(payloads(unbound)).isEmpty();
                    }
                    try (var scope = ProfileDatabaseScope.enter("beta"); var connection = source.getConnection()) {
                        assertThat(backend(connection)).isEqualTo(backend);
                        assertThat(payloads(connection)).containsExactly("beta-private-history");
                    }
                    try (var unbound = pool.getConnection()) {
                        assertThat(backend(unbound)).isEqualTo(backend);
                        assertThat(payloads(unbound)).isEmpty();
                    }
                }
            } finally {
                sql.execute("DELETE FROM sessions WHERE id IN ('" + alpha + "','" + beta + "')");
                sql.execute("REVOKE SELECT ON sessions,messages FROM " + role);
                sql.execute("REVOKE USAGE ON SCHEMA public FROM " + role);
                sql.execute("DROP ROLE " + role);
            }
        }
    }

    private static int backend(Connection connection) throws SQLException {
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT pg_backend_pid()")) {
            if (!rows.next()) {
                throw new SQLException("PostgreSQL did not return its backend ID");
            }
            return rows.getInt(1);
        }
    }

    private static List<String> payloads(Connection connection) throws SQLException {
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("""
            SELECT m.content FROM messages m JOIN sessions s ON s.id=m.session_id
            WHERE s.user_id='fixture-history'
            ORDER BY m.content
            """)) {
            var result = new java.util.ArrayList<String>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return result;
        }
    }
}
