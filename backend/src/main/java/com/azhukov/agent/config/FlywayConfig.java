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
            .baselineOnMigrate(true)
            // V21.1 (ensure bot_sessions) was added after V22..V29 were already applied
            // on production. Allow out-of-order application so fresh installs and the
            // existing production history both converge to the same schema.
            .outOfOrder(true);

        if (isH2(dataSource)) {
            // H2 (tests / noop profile): no tsvector/GIN, no partial indexes.
            // Vendor variants live in db/h2; FTS queries fall back to LIKE in
            // SessionSearchService.
            config.locations("classpath:db/migration", "classpath:db/h2")
                .initSql("CREATE DOMAIN IF NOT EXISTS timestamptz AS TIMESTAMP WITH TIME ZONE; CREATE DOMAIN IF NOT EXISTS jsonb AS JSON");
        } else {
            // PostgreSQL: vendor-specific scripts (tsvector FTS, partial indexes)
            // live in db/postgresql on top of the common set.
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
