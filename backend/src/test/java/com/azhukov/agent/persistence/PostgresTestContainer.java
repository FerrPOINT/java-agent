package com.azhukov.agent.persistence;

import com.azhukov.agent.support.BackendIntegrationTestBase;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL Testcontainer configuration for integration tests.
 * Subclass this to get real PostgreSQL with Flyway migrations.
 * An explicitly supplied disposable Compose database avoids nested Docker.
 *
 * The container is a JVM-wide SINGLETON (started once, reused by every test
 * class). Per-class @Container containers broke Spring's context cache: each
 * subclass got a fresh container URL, but the cached ApplicationContext kept
 * the old HikariPool pointed at the previous (stopped) container —
 * "Connection is not available / connection refused" after the first class.
 * With one shared container the cached context stays valid across classes.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration",
    "agent.model.provider=noop",
    "agent.memory.enabled=false",
    "agent.skills.enabled=false",
    "agent.mcp.enabled=false",
    "agent.mcp.servers=",
    "agent.chromium.auto-start=false",
    "agent.chromium.auto-install=false"
})
public abstract class PostgresTestContainer extends BackendIntegrationTestBase {

    private static final String EXTERNAL_URL = System.getenv("JAVA_AGENT_TEST_JDBC_URL");
    private static final PostgreSQLContainer<?> POSTGRES = startDatabase();

    private static PostgreSQLContainer<?> startDatabase() {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            if (!EXTERNAL_URL.matches("jdbc:postgresql://[^/?#]+/java_agent_test_[a-z0-9_]+")
                || System.getenv("JAVA_AGENT_TEST_DB_USER") == null
                || System.getenv("JAVA_AGENT_TEST_DB_PASSWORD") == null) {
                throw new IllegalArgumentException("explicit disposable java_agent_test_ database is required");
            }
            return null;
        }
        PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("test").withUsername("test").withPassword("test").withReuse(true);
        database.start(); // JVM singleton; Ryuk owns only this Testcontainers path.
        return database;
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES == null ? EXTERNAL_URL : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> POSTGRES == null
            ? System.getenv("JAVA_AGENT_TEST_DB_USER") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> POSTGRES == null
            ? System.getenv("JAVA_AGENT_TEST_DB_PASSWORD") : POSTGRES.getPassword());
    }
}
