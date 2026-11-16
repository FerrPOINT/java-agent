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
                .locations("filesystem:" + path).baselineOnMigrate(true).baselineVersion("72").target("73").load();
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
    void h2BackgroundOwnerMigrationPreservesKnownAndUnattributedHistory() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:background_migration_" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL");
        config.setUsername("sa");
        try (var source = new HikariDataSource(config);
             var connection = source.getConnection();
             var sql = connection.createStatement()) {
            sql.execute("CREATE DOMAIN timestamptz AS TIMESTAMP WITH TIME ZONE");
            sql.execute("CREATE TABLE sessions (id UUID PRIMARY KEY, user_id TEXT NOT NULL)");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                new org.springframework.core.io.ClassPathResource("db/migration/V32__background_jobs.sql"));
            var session = java.util.UUID.randomUUID();
            var owned = java.util.UUID.randomUUID();
            var unknown = java.util.UUID.randomUUID();
            sql.execute("INSERT INTO sessions VALUES ('" + session + "','owner-a')");
            sql.execute("INSERT INTO background_jobs(id,session_id,prompt,status,result) VALUES ('"
                + owned + "','" + session + "','keep prompt','DONE','keep result'), ('"
                + unknown + "',NULL,'legacy work','RUNNING',NULL)");
            var migration = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration", "classpath:db/h2").baselineVersion("76").load();
            migration.baseline();
            migration.migrate();

            try (var row = sql.executeQuery("SELECT user_id,prompt,status,result FROM background_jobs WHERE id='" + owned + "'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("owner-a");
                assertThat(row.getString(2)).isEqualTo("keep prompt");
                assertThat(row.getString(3)).isEqualTo("DONE");
                assertThat(row.getString(4)).isEqualTo("keep result");
            }
            sql.execute("UPDATE background_jobs SET status='DONE',result='legacy completion' WHERE id='" + unknown + "'");
            try (var row = sql.executeQuery("SELECT user_id,result FROM background_jobs WHERE id='" + unknown + "'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isNull();
                assertThat(row.getString(2)).isEqualTo("legacy completion");
            }
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
