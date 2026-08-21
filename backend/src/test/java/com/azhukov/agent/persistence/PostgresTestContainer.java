package com.azhukov.agent.persistence;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
public abstract class PostgresTestContainer {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    static {
        POSTGRES.start(); // singleton — started once per JVM, Ryuk cleans up
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
