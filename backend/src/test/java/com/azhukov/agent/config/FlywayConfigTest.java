package com.azhukov.agent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayConfigTest {

    @Test
    void h2CronTableMigrationIsVendorNeutral() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:cron_migration_" + java.util.UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL");
        config.setDriverClassName("org.h2.Driver");
        config.setUsername("sa");
        try (var dataSource = new HikariDataSource(config)) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE DOMAIN IF NOT EXISTS timestamptz AS TIMESTAMP WITH TIME ZONE");
            } catch (java.sql.SQLException e) { throw new AssertionError(e); }
            var path = java.nio.file.Path.of("src/main/resources/db/migration").toAbsolutePath();
            var flyway = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:" + path).baselineOnMigrate(true).baselineVersion("72").load();
            flyway.baseline();
            flyway.migrate();
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("select count(*) from cron_suggestions")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            } catch (java.sql.SQLException e) { throw new AssertionError(e); }
        }
    }

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
