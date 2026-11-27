package com.azhukov.agent.config;

import com.azhukov.agent.core.security.ProfileDatabaseScope;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Bind a PostgreSQL connection lease to the selected application profile. */
@Component
public class ProfileScopeDataSource implements BeanPostProcessor {

    private final boolean configuredProfiles;

    public ProfileScopeDataSource(Environment environment) {
        configuredProfiles = StringUtils.hasText(environment.getProperty("agent.profile.base-dir"));
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        return bean instanceof DataSource source && !(source instanceof ScopedDataSource)
            ? new ScopedDataSource(source, configuredProfiles) : bean;
    }

    static final class ScopedDataSource extends DelegatingDataSource {

        private static final String SET_PROFILE = "SELECT set_config('pdlc.java.profile', ?, false)";
        private static final String SET_PROFILE_WITH_POLICY_CHECK = SET_PROFILE + """
            , NOT EXISTS (
                SELECT 1
                FROM (VALUES
                    ('openai_run_states'), ('openai_run_events'), ('sessions'), ('messages'),
                    ('session_cli_state'), ('memory'), ('memory_pending'), ('memory_write_audit'),
                    ('pending_review_summaries'), ('attachment_artifacts'), ('mcp_server_configs'),
                    ('mcp_schema_cache'), ('mcp_oauth_tokens'), ('mcp_oauth_flows')
                ) AS stores(name)
                WHERE row_security_active(to_regclass(stores.name)) IS DISTINCT FROM TRUE
            )
            """;

        private final boolean configuredProfiles;
        private final Path defaultProfiles = AgentProperties.ProfileProperties.defaultHome().resolve("profiles");

        ScopedDataSource(DataSource source, boolean configuredProfiles) {
            super(source);
            this.configuredProfiles = configuredProfiles;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return scoped(super.getConnection());
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return scoped(super.getConnection(user, password));
        }

        private Connection scoped(Connection connection) throws SQLException {
            try {
                String profile = ProfileDatabaseScope.current();
                if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                    return connection;
                }
                if (!connection.getAutoCommit()) {
                    throw new SQLException("Profile-scoped connection must be idle on checkout");
                }
                bindProfile(connection, profile == null ? "" : profile,
                    configuredProfiles || Files.isDirectory(defaultProfiles));
                if (profile == null) {
                    return connection;
                }
                return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            close(connection);
                            return null;
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            } catch (SQLException | RuntimeException e) {
                try {
                    close(connection);
                } catch (SQLException closeError) {
                    e.addSuppressed(closeError);
                }
                throw e;
            }
        }

        private static void close(Connection connection) throws SQLException {
            if (connection.isClosed()) {
                return;
            }
            try (connection) {
                try {
                    if (!connection.getAutoCommit()) {
                        connection.rollback();
                        connection.setAutoCommit(true);
                    }
                    bindProfile(connection, "", false);
                } catch (SQLException resetFailure) {
                    try {
                        connection.abort(Runnable::run);
                    } catch (SQLException abortFailure) {
                        resetFailure.addSuppressed(abortFailure);
                    }
                    throw resetFailure;
                }
            }
        }

        private static void bindProfile(Connection connection, String profile, boolean requirePolicies) throws SQLException {
            try (var bind = connection.prepareStatement(requirePolicies ? SET_PROFILE_WITH_POLICY_CHECK : SET_PROFILE)) {
                bind.setString(1, profile);
                try (var result = bind.executeQuery()) {
                    if (requirePolicies && (!result.next() || !result.getBoolean(2))) {
                        throw new SQLException(
                            "Profile storage requires active PostgreSQL row security. Apply migrations separately "
                                + "and connect as the application role without table ownership or BYPASSRLS",
                            "42501");
                    }
                }
            }
        }
    }
}
