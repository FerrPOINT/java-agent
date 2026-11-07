package com.azhukov.agent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentFlywayConfigTest {
    @Test
    void h2ArtifactMigrationsPreserveSessionDedupeIncludingNull() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:artifact_migration_" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL");
        config.setUsername("sa");
        try (var source = new HikariDataSource(config);
             var connection = source.getConnection();
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE attachment_artifacts (id INT PRIMARY KEY, owner_id VARCHAR, "
                + "content_hash VARCHAR, disposition VARCHAR, session_id UUID)");
            sql.execute("CREATE UNIQUE INDEX uq_attachment_owner_hash_disposition "
                + "ON attachment_artifacts(owner_id,content_hash,disposition)");
            sql.execute("INSERT INTO attachment_artifacts VALUES (1,'owner','hash','photo',NULL)");

            var migration = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration", "classpath:db/h2").baselineVersion("74").load();
            migration.baseline();
            migration.migrate();

            try (var existing = sql.executeQuery("SELECT outbound_claimed_at FROM attachment_artifacts WHERE id=1")) {
                assertThat(existing.next()).isTrue();
                assertThat(existing.getObject(1)).isNull();
            }
            sql.execute("INSERT INTO attachment_artifacts VALUES (2,'owner','hash','photo',RANDOM_UUID(),NULL)");
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                sql.execute("INSERT INTO attachment_artifacts VALUES (3,'owner','hash','photo',NULL,NULL)"))
                .isInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
        }
    }
}
