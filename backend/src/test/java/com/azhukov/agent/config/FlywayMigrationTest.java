package com.azhukov.agent.config;

import org.flywaydb.core.Flyway;
import com.azhukov.agent.persistence.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration,classpath:db/postgresql",
    "spring.jpa.hibernate.ddl-auto=none",
    "agent.model.provider=noop",
    "agent.memory.enabled=false",
    "agent.skills.enabled=false",
    "agent.mcp.enabled=false",
    "agent.mcp.servers=",
    "agent.chromium.auto-start=false",
    "agent.chromium.auto-install=false"
})
@Tag("slow")
class FlywayMigrationTest extends PostgresTestContainer {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void flywayMigrationsRunSuccessfully() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void allMigrationFilesAreApplied() {
        List<String> versions = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY version", String.class);

        assertThat(versions).contains("1", "2", "3", "4", "5", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42", "43", "44");
    }

    @Test
    void keyTablesAreCreated() throws Exception {
        Set<String> tables = new HashSet<>();
        DatabaseMetaData metaData = dataSource.getConnection().getMetaData();
        try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase());
            }
        }

        assertThat(tables)
            .contains("sessions", "messages", "memory", "skills", "todos", "audit_log", "delegated_task_runs");
    }

    @Test
    void sessionProfileColumnIsMigrated() {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_name = 'sessions'
              AND column_name = 'profile'
            """, Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void cronProfileColumnIsMigrated() {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_name = 'cron_jobs'
              AND column_name = 'profile'
            """, Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void cronModelSnapshotColumnsAreMigrated() {
        List<String> columns = jdbcTemplate.queryForList("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_name = 'cron_jobs'
              AND column_name IN ('provider_snapshot', 'model_snapshot')
            ORDER BY column_name
            """, String.class);

        assertThat(columns).containsExactlyInAnyOrder("provider_snapshot", "model_snapshot");
    }

    @Test
    void delegatedTaskRunDeliveryColumnsAreMigrated() {
        List<String> columns = jdbcTemplate.queryForList("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_name = 'delegated_task_runs'
              AND column_name IN (
                'delivered_at',
                'delivery_target',
                'delivery_error',
                'delivery_attempts',
                'delivery_idempotency_key',
                'delivery_claim',
                'delivery_claimed_at',
                'delivery_dropped_at'
              )
            ORDER BY column_name
            """, String.class);

        assertThat(columns).containsExactlyInAnyOrder(
            "delivered_at",
            "delivery_target",
            "delivery_error",
            "delivery_attempts",
            "delivery_idempotency_key",
            "delivery_claim",
            "delivery_claimed_at",
            "delivery_dropped_at");
    }
}
