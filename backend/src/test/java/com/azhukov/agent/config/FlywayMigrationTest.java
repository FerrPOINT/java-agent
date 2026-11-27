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
import java.util.UUID;

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
    void upgradesPopulatedAttachmentOwnershipWithoutChangingArtifactsOrChatHistory() throws Exception {
        String schema = "pdlc31_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var migration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration", "classpath:db/postgresql");
        migration.target("80").load().migrate();

        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            connection.setSchema(schema);
            sql.execute("""
                INSERT INTO sessions(id,user_id,model_provider,model_name,profile) VALUES
                ('31111111-1111-4111-8111-111111111111','owner','openai','test-model','alpha'),
                ('32222222-2222-4222-8222-222222222222','owner','openai','test-model','beta')
                """);
            sql.execute("""
                INSERT INTO messages (session_id,role,content) VALUES
                ('31111111-1111-4111-8111-111111111111','user','alpha retained turn'),
                ('32222222-2222-4222-8222-222222222222','user','beta retained turn')
                """);
            sql.execute("""
                INSERT INTO attachment_artifacts
                (id,owner_id,profile,session_id,content_hash,origin,disposition,state,cache_path,delivered_message_id)
                VALUES
                ('att_alpha','owner','default','31111111-1111-4111-8111-111111111111',
                    'shared-hash','telegram','document','delivered','assets/old-a.dat','original-message'),
                ('att_beta','owner','beta','32222222-2222-4222-8222-222222222222',
                    'shared-hash','cli','document','received','assets/old-b.dat',NULL),
                ('att_orphan','owner','default',NULL,
                    'orphan-hash','internal','file','received','assets/orphan.dat',NULL),
                ('att_wrong_owner','other-owner','default','31111111-1111-4111-8111-111111111111',
                    'shared-hash','telegram','document','received','assets/other.dat',NULL)
                """);

            assertThat(migration.target("81").load().migrate().migrationsExecuted).isEqualTo(1);

            try (var rows = sql.executeQuery("""
                SELECT a.id,a.profile,a.cache_path,a.delivered_message_id,m.content
                FROM attachment_artifacts a LEFT JOIN messages m ON m.session_id=a.session_id
                ORDER BY a.id
                """)) {
                assertThat(rows.next()).isTrue();
                assertThat(List.of(rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getString(4), rows.getString(5))).containsExactly(
                    "att_alpha", "alpha", "assets/old-a.dat", "original-message", "alpha retained turn");
                assertThat(rows.next()).isTrue();
                assertThat(List.of(rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getString(5))).containsExactly("att_beta", "beta", "assets/old-b.dat", "beta retained turn");
                assertThat(rows.next()).isTrue();
                assertThat(List.of(rows.getString(1), rows.getString(2), rows.getString(3)))
                    .containsExactly("att_orphan", "default", "assets/orphan.dat");
                assertThat(rows.next()).isTrue();
                assertThat(List.of(rows.getString(1), rows.getString(2), rows.getString(3)))
                    .containsExactly("att_wrong_owner", "default", "assets/other.dat");
                assertThat(rows.next()).isFalse();
            }
            sql.execute("""
                INSERT INTO attachment_artifacts(id,owner_id,profile,content_hash,origin,disposition)
                VALUES ('att_alpha_free','owner','alpha','same-free-hash','internal','file'),
                       ('att_beta_free','owner','beta','same-free-hash','internal','file')
                """);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> sql.execute("""
                INSERT INTO attachment_artifacts(id,owner_id,profile,content_hash,origin,disposition)
                VALUES ('att_alpha_duplicate','owner','alpha','same-free-hash','internal','file')
                """)).isInstanceOf(org.postgresql.util.PSQLException.class)
                .hasMessageContaining("uq_attachment_profile_dedup");
        }
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
