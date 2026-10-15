package com.azhukov.agent.persistence;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisposablePostgresTest {
    private Map<String, String> fixture() {
        return new HashMap<>(Map.of(
            "JAVA_AGENT_TEST_DB_URL", "jdbc:postgresql://owned-postgres:5432/qa",
            "JAVA_AGENT_TEST_DB_USER", "qa",
            "JAVA_AGENT_TEST_DB_PASSWORD", "ephemeral",
            "JAVA_AGENT_TEST_DB_DISPOSABLE", "true"));
    }

    @Test
    void noExternalConfigurationKeepsTestcontainers() {
        assertThat(DisposablePostgres.fromEnvironment(Map.of())).isEmpty();
    }

    @Test
    void explicitOwnedFixtureIsUsedWithoutStartingDocker() {
        var database = DisposablePostgres.fromEnvironment(fixture()).orElseThrow();
        assertThat(database.url()).isEqualTo("jdbc:postgresql://owned-postgres:5432/qa");
        assertThat(database.username()).isEqualTo("qa");
    }

    @Test
    void missingOwnershipConsentOrCredentialsFailsClosed() {
        for (String key : fixture().keySet()) {
            var environment = fixture();
            environment.remove(key);
            assertThatThrownBy(() -> DisposablePostgres.fromEnvironment(environment))
                .isInstanceOf(IllegalArgumentException.class);
        }
        var environment = fixture();
        environment.put("JAVA_AGENT_TEST_DB_DISPOSABLE", "false");
        assertThatThrownBy(() -> DisposablePostgres.fromEnvironment(environment))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDifferentDatabaseEngineIsNotAccepted() {
        var environment = fixture();
        environment.put("JAVA_AGENT_TEST_DB_URL", "jdbc:h2:mem:test");
        assertThatThrownBy(() -> DisposablePostgres.fromEnvironment(environment))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
