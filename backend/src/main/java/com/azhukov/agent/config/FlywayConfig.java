package com.azhukov.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        var config = Flyway.configure()
            .dataSource(dataSource)
            .baselineOnMigrate(true);

        if (isH2(dataSource)) {
            // H2 (tests / noop profile): H2 has no tsvector/GIN support, so
            // vendor-specific migrations live in db/h2 (V18 FTS stub).
            // Runtime FTS queries already have LIKE-based fallbacks in
            // SessionSearchService, so search still works on H2.
            config.locations("classpath:db/migration", "classpath:db/h2")
                .initSql("CREATE DOMAIN IF NOT EXISTS timestamptz AS TIMESTAMP WITH TIME ZONE; CREATE DOMAIN IF NOT EXISTS jsonb AS JSON");
        } else {
            // PostgreSQL: db/postgresql adds vendor-specific scripts
            // (V18 tsvector/GIN full-text search) on top of the common set.
            config.locations("classpath:db/migration", "classpath:db/postgresql");
        }

        return config.load();
    }

    private static boolean isH2(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            return url != null && url.contains(":h2:");
        } catch (SQLException e) {
            log.warn("Failed to determine database type: {}", e.getMessage());
            return false;
        }
    }
}
