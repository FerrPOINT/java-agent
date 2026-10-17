package com.azhukov.agent.persistence;

import com.azhukov.agent.support.BackendIntegrationTestBase;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL Testcontainer configuration for integration tests.
 * Subclass this to get a real PostgreSQL 16 container with Flyway migrations.
 *
 * The container is a JVM-wide SINGLETON (started once, reused by every test
 * class). Per-class @Container containers broke Spring's context cache: each
 * subclass got a fresh container URL, but the cached ApplicationContext kept
 * the old HikariPool pointed at the previous (stopped) container —
 * "Connection is not available / connection refused" after the first class.
 * With one shared container the cached context stays valid across classes.
 */
@Testcontainers
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

    private static final DisposablePostgres DATABASE =
        DisposablePostgres.fromEnvironment(System.getenv()).map(DisposablePostgres::isolatedSchema).orElseGet(() -> {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true);

            postgres.start(); // singleton — started once per JVM, Ryuk cleans up
            return new DisposablePostgres(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        });

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
    }
}
