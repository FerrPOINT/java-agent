package com.azhukov.agent.persistence;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Explicit test-runner contract; never infer permission to mutate an external DB. */
record DisposablePostgres(String url, String username, String password) {
    DisposablePostgres isolatedSchema() {
        String schema = "qa_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot prepare isolated disposable PostgreSQL schema", failure);
        }
        return new DisposablePostgres(url + (url.contains("?") ? "&" : "?")
            + "currentSchema=" + schema + ",public", username, password);
    }

    static Optional<DisposablePostgres> fromEnvironment(Map<String, String> environment) {
        String prefix = "JAVA_AGENT_TEST_DB_";
        String url = environment.get(prefix + "URL");
        String user = environment.get(prefix + "USER");
        String password = environment.get(prefix + "PASSWORD");
        String disposable = environment.get(prefix + "DISPOSABLE");
        if (url == null && user == null && password == null && disposable == null) {
            return Optional.empty();
        }
        if (!"true".equals(disposable) || url == null || !url.startsWith("jdbc:postgresql://")
                || user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Explicit disposable PostgreSQL URL, user and password are required");
        }
        return Optional.of(new DisposablePostgres(url, user, password));
    }
}
