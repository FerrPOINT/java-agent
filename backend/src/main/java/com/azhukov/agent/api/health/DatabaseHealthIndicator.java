package com.azhukov.agent.api.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(5)) {
                return Health.up().build();
            }
            return Health.down().withDetail("reason", "connection not valid").build();
        } catch (Exception e) {
            log.warn("Database health check failed", e);
            return Health.down().withDetail("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName()).build();
        }
    }
}
