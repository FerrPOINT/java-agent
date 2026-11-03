package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.CronSuggestionEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("slow")
@Transactional
class CronSuggestionRepositoryTest extends PostgresTestContainer {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T12:00:00Z");

    @Autowired
    private CronSuggestionRepository cronSuggestionRepository;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Test
    void cronSuggestionsAreRegisteredAsIdentityReferences() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select count(*) from java_identity_reference_sources where source = 'cron_suggestions' and 'user_id' = any(user_columns)", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid = 'cron_suggestions'::regclass and tgname = 'z_identity_reference_guard' and not tgisinternal", Integer.class)).isEqualTo(1);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void upgradesMainSchemaFromV72ToCronSuggestionsV73() {
        String schema = "cron_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/postgresql").target("72").load().migrate();
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".flyway_schema_history where version='72' and success", Integer.class)).isEqualTo(1);
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".flyway_schema_history where version='73' and success", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".cron_suggestions", Integer.class)).isZero();
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void legacyCronV72CannotSilentlyUpgradeAsIdentityV72(@org.junit.jupiter.api.io.TempDir java.nio.file.Path legacyPostgres) throws Exception {
        String schema = "cron_legacy_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var postgresMigrations = java.nio.file.Path.of(getClass().getResource("/db/postgresql").toURI());
        try {
            try (var paths = java.nio.file.Files.list(postgresMigrations)) {
                for (var path : paths.filter(p -> !p.getFileName().toString().startsWith("V72__")).toList()) {
                    java.nio.file.Files.copy(path, legacyPostgres.resolve(path.getFileName()));
                }
            }
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:legacy-cron", "filesystem:" + legacyPostgres).target("72").load().migrate();
            jdbc.update("insert into " + schema + ".cron_suggestions(id,user_id,title,source,job_name,job_schedule,job_prompt,dedup_key) values (?,?,?,?,?,?,?,?)",
                java.util.UUID.randomUUID(), "owner", "title", "test", "job", "0 9 * * *", "prompt", "key");
            assertThatThrownBy(() -> org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration", "classpath:db/postgresql")
                .load().migrate()).isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class)
                .hasMessageContaining("Migration checksum mismatch for migration version 72");
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".cron_suggestions", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Autowired
    private com.azhukov.agent.service.CronSuggestionService suggestionService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void dismissCannotOverwriteConcurrentAcceptance() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        String owner = "race-" + java.util.UUID.randomUUID();
        var id = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        var acquired = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var accept = pool.submit(() -> tx.execute(s -> {
                var entity = cronSuggestionRepository.findByIdAndUserIdForUpdate(id, owner).orElseThrow();
                acquired.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                entity.setStatus("accepted");
                return null;
            }));
            assertThat(acquired.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var dismiss = pool.submit(() -> {
                com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
                try { return suggestionService.dismissSuggestion(id.toString()); }
                finally { com.azhukov.agent.core.security.UserContext.clear(); }
            });
            try {
                assertThatThrownBy(() -> dismiss.get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            accept.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(dismiss.get(10, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            String status = tx.execute(s -> cronSuggestionRepository.findById(id).orElseThrow().getStatus());
            assertThat(status).isEqualTo("accepted");
        } finally {
            release.countDown();
            tx.executeWithoutResult(s -> cronSuggestionRepository.deleteById(id));
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentIdenticalOffersReturnOneRecordWithoutTransactionFailure() throws Exception {
        String owner = "duplicate-" + java.util.UUID.randomUUID();
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var spec = new com.azhukov.agent.service.CronSuggestionService.JobSpec("job", "0 9 * * *", "prompt", "local", null);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<com.azhukov.agent.service.CronSuggestionService.SuggestionRecord> work = () -> {
                com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
                try {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    return suggestionService.addSuggestion("title", "description", "catalog", spec, "same");
                } finally { com.azhukov.agent.core.security.UserContext.clear(); }
            };
            var a = pool.submit(work);
            var b = pool.submit(work);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = java.util.Arrays.asList(a.get(20, java.util.concurrent.TimeUnit.SECONDS), b.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(java.util.Objects::nonNull)).hasSize(1);
        } finally {
            start.countDown();
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s ->
                cronSuggestionRepository.deleteAll(cronSuggestionRepository.findByUserIdOrderByCreatedAtAsc(owner)));
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void rolledBackAcceptanceMustNotLeaveScheduledJob() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var properties = org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "properties");
        var cron = ((com.azhukov.agent.config.AgentProperties) properties).getCron();
        boolean previous = cron.isEnabled();
        String owner = "rollback-" + java.util.UUID.randomUUID();
        var id = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        try {
            cron.setEnabled(true);
            com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
            var jobId = tx.execute(status -> {
                var job = suggestionService.acceptSuggestion(id.toString());
                assertThat(job).isNotNull();
                status.setRollbackOnly();
                return job.getId();
            });
            assertThat(cronJobRepository.findById(jobId)).isEmpty();
            @SuppressWarnings("unchecked")
            var tasks = (java.util.Map<java.util.UUID, ?>) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduledTasks");
            assertThat(tasks).doesNotContainKey(jobId);
            assertThat(cronSuggestionRepository.findById(id).orElseThrow().getStatus()).isEqualTo("pending");
        } finally {
            com.azhukov.agent.core.security.UserContext.clear();
            cron.setEnabled(previous);
            tx.executeWithoutResult(s -> cronSuggestionRepository.deleteById(id));
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void committedAcceptanceSchedulesPersistedJobOnce() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "properties");
        boolean previous = properties.getCron().isEnabled();
        String owner = "commit-" + java.util.UUID.randomUUID();
        var id = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        java.util.UUID jobId = null;
        try {
            properties.getCron().setEnabled(true);
            com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
            jobId = suggestionService.acceptSuggestion(id.toString()).getId();
            assertThat(suggestionService.acceptSuggestion(id.toString())).isNull();
            var persisted = cronJobRepository.findById(jobId).orElseThrow();
            assertThat(persisted.getUserId()).isEqualTo(owner);
            assertThat(persisted.getNextRunAt()).isNotNull();
            @SuppressWarnings("unchecked")
            var tasks = (java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>>) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduledTasks");
            assertThat(tasks).containsKey(jobId);
        } finally {
            com.azhukov.agent.core.security.UserContext.clear();
            properties.getCron().setEnabled(previous);
            @SuppressWarnings("unchecked")
            var tasks = (java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>>) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduledTasks");
            if (jobId != null) {
                var future = tasks.remove(jobId);
                if (future != null) future.cancel(false);
                var cleanupJobId = jobId;
                tx.executeWithoutResult(s -> cronJobRepository.deleteById(cleanupJobId));
            }
            tx.executeWithoutResult(s -> cronSuggestionRepository.deleteById(id));
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void startupRearmsCommittedJobMissingItsInMemorySchedule() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "properties");
        boolean previous = properties.getCron().isEnabled();
        String owner = "startup-" + java.util.UUID.randomUUID();
        var id = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        java.util.UUID jobId = null;
        try {
            properties.getCron().setEnabled(false);
            com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
            jobId = suggestionService.acceptSuggestion(id.toString()).getId();
            assertThat(cronJobRepository.findById(jobId)).isPresent();
            @SuppressWarnings("unchecked")
            var tasks = (java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>>) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduledTasks");
            assertThat(tasks).doesNotContainKey(jobId);
            properties.getCron().setEnabled(true);
            cronJobService.init();
            assertThat(tasks).containsKey(jobId);
            assertThat(cronJobRepository.findById(jobId).orElseThrow().getNextRunAt()).isNotNull();
        } finally {
            com.azhukov.agent.core.security.UserContext.clear();
            properties.getCron().setEnabled(previous);
            @SuppressWarnings("unchecked")
            var tasks = (java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>>) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduledTasks");
            if (jobId != null) {
                var future = tasks.remove(jobId);
                if (future != null) future.cancel(false);
                var cleanupJobId = jobId;
                tx.executeWithoutResult(s -> cronJobRepository.deleteById(cleanupJobId));
            }
            tx.executeWithoutResult(s -> cronSuggestionRepository.deleteById(id));
        }
    }

    @Autowired
    private com.azhukov.agent.service.CronJobService cronJobService;
    @Autowired
    private CronJobRepository cronJobRepository;

    @Test
    void ownerScopedQueriesDoNotLeakOrReuseAnotherUsersDedupKey() {
        CronSuggestionEntity alice = cronSuggestionRepository.save(newSuggestion("alice", "daily-briefing"));
        CronSuggestionEntity bob = cronSuggestionRepository.save(newSuggestion("bob", "daily-briefing"));

        assertThat(cronSuggestionRepository.findByUserIdAndStatusOrderByCreatedAtAsc("alice", "pending"))
            .extracting(CronSuggestionEntity::getId)
            .containsExactly(alice.getId());
        assertThat(cronSuggestionRepository.findByIdAndUserId(bob.getId(), "alice")).isEmpty();
        assertThat(cronSuggestionRepository.findByUserIdAndDedupKey("alice", "daily-briefing"))
            .contains(alice);
        assertThat(cronSuggestionRepository.findByUserIdAndDedupKey("bob", "daily-briefing"))
            .contains(bob);
    }

    @Test
    void sameOwnerCannotPersistTheSameDedupKeyTwice() {
        cronSuggestionRepository.saveAndFlush(newSuggestion("alice", "daily-briefing"));

        assertThatThrownBy(() -> cronSuggestionRepository.saveAndFlush(newSuggestion("alice", "daily-briefing")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static CronSuggestionEntity newSuggestion(String userId, String dedupKey) {
        CronSuggestionEntity entity = new CronSuggestionEntity();
        entity.setUserId(userId);
        entity.setTitle("Daily briefing");
        entity.setDescription("description");
        entity.setSource("catalog");
        entity.setJobName("daily-briefing");
        entity.setJobSchedule("0 8 * * *");
        entity.setJobPrompt("prompt");
        entity.setDeliverTo("local");
        entity.setDedupKey(dedupKey);
        entity.setStatus("pending");
        entity.setCreatedAt(CREATED_AT);
        return entity;
    }
}
