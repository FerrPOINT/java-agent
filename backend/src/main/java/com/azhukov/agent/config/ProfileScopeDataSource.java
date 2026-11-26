package com.azhukov.agent.config;

import com.azhukov.agent.core.security.ProfileDatabaseScope;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

/** Bind a PostgreSQL connection lease to the selected application profile. */
@Component
public class ProfileScopeDataSource implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        return bean instanceof DataSource source && !(source instanceof ScopedDataSource)
            ? new ScopedDataSource(source) : bean;
    }

    static final class ScopedDataSource extends DelegatingDataSource {

        ScopedDataSource(DataSource source) {
            super(source);
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
                configure(connection, profile == null ? "" : profile);
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
                    configure(connection, "");
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

        private static void configure(Connection connection, String profile) throws SQLException {
            try (var bind = connection.prepareStatement("SELECT set_config('pdlc.java.profile', ?, false)")) {
                bind.setString(1, profile);
                bind.execute();
            }
        }
    }
}
