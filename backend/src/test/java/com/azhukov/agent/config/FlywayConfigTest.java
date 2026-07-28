package com.azhukov.agent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayConfigTest {

    @Test
    void createsFlywayForH2() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:flywaycfg;DB_CLOSE_DELAY=-1");
        config.setDriverClassName("org.h2.Driver");
        config.setUsername("sa");
        DataSource dataSource = new HikariDataSource(config);

        FlywayConfig cfg = new FlywayConfig();
        Flyway flyway = cfg.flyway(dataSource);

        assertThat(flyway).isNotNull();
    }
}
