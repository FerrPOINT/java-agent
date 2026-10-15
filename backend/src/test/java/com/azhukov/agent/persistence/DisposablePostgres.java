package com.azhukov.agent.persistence;

import java.util.Map;
import java.util.Optional;

/** Explicit test-runner contract; never infer permission to mutate an external DB. */
record DisposablePostgres(String url, String username, String password) {
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
