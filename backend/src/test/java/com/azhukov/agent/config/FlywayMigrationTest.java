package com.azhukov.agent.config;

import org.flywaydb.core.Flyway;
import com.azhukov.agent.persistence.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

        assertThat(versions).contains("1", "2", "3", "4", "5", "35", "69", "70", "71", "72");
    }

    @Test
    void messagesIncludesCompleteToolCallBatchColumn() throws Exception {
        Set<String> columns = new HashSet<>();
        DatabaseMetaData metaData = dataSource.getConnection().getMetaData();
        try (ResultSet rs = metaData.getColumns(null, null, "messages", "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
        }

        assertThat(columns).contains("tool_calls_json");
    }

    @Test
    void memoryWriteAuditContainsAffectedMemoryId() throws Exception {
        Set<String> columns = new HashSet<>();
        DatabaseMetaData metaData = dataSource.getConnection().getMetaData();
        try (ResultSet rs = metaData.getColumns(null, null, "memory_write_audit", "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
        }
        assertThat(columns).contains("memory_id");
    }

    @Test
    void upgradePreservesLegacyRowsAndAssignsOrderOnlyToNewMessages() throws Exception {
        String schema = "message_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try {
            var old = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/postgresql").target("78").load();
            old.migrate();
            var session = java.util.UUID.randomUUID();
            var legacy = java.util.UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO " + schema + ".sessions(id,user_id,model_provider,model_name) VALUES (?,?,?,?)", session, "migration-fixture", "noop", "noop");
            jdbcTemplate.update("INSERT INTO " + schema + ".messages(id,session_id,role,content) VALUES (?,?,?,?)", legacy, session, "user", "legacy-fixture");
            var upgraded = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/postgresql").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            upgraded.validate();
            var oldRow = jdbcTemplate.queryForMap("SELECT content,insert_order FROM " + schema + ".messages WHERE id=?", legacy);
            assertThat(oldRow.get("content")).isEqualTo("legacy-fixture");
            assertThat(oldRow.get("insert_order")).isNull();
            var fresh = java.util.UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO " + schema + ".messages(id,session_id,role,content) VALUES (?,?,?,?)", fresh, session, "assistant", "new-fixture");
            assertThat(jdbcTemplate.queryForObject("SELECT insert_order FROM " + schema + ".messages WHERE id=?", Long.class, fresh)).isNotNull();
        } finally { jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE"); }
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
            .contains("sessions", "messages", "memory", "skills", "todos", "audit_log",
                "memory_write_audit", "pending_review_summaries", "cron_suggestions");
    }
}
