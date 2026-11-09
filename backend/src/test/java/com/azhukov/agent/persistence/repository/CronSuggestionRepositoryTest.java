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

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.azhukov.agent.service.AgentRuntimeService runtime;

    @Test
    void cronSuggestionsAreRegisteredAsIdentityReferences() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select count(*) from java_identity_reference_sources where source = 'cron_suggestions' and 'user_id' = any(user_columns)", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid = 'cron_suggestions'::regclass and tgname = 'z_identity_reference_guard' and not tgisinternal", Integer.class)).isEqualTo(1);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void upgradesV73CronDataAndRestoresImmutableInventory() {
        String schema = "cron_v73_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var id = java.util.UUID.randomUUID();
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/postgresql").target("73").load().migrate();
            jdbc.update("insert into " + schema + ".cron_suggestions(id,user_id,title,source,job_name,job_schedule,job_prompt,dedup_key) values (?,?,?,?,?,?,?,?)",
                id, "owner", "title", "test", "job", "0 9 * * *", "prompt", "key");
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".cron_suggestions where id=? and user_id='owner'", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".java_identity_reference_sources where source='cron_suggestions'", Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update("delete from " + schema + ".java_identity_reference_sources where source='cron_suggestions'"))
                .hasStackTraceContaining("identity_provenance_immutable");
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"71", "72"})
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void upgradesPopulatedMainSchema(String previousVersion) {
        String schema = "cron_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/postgresql").target(previousVersion).load().migrate();
            var id = java.util.UUID.randomUUID();
            jdbc.update("insert into " + schema + ".cron_jobs (id, name, schedule, prompt) values (?, ?, ?, ?)",
                id, "existing job", "every 1h", "keep this prompt");
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".flyway_schema_history where version='73' and success", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".flyway_schema_history where version='74' and success", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".cron_suggestions", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select prompt from " + schema + ".cron_jobs where id=?", String.class, id))
                .isEqualTo("keep this prompt");
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
                try { return suggestionService.dismissSuggestion(id); }
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
                var job = suggestionService.acceptSuggestion(id);
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

    @org.springframework.beans.factory.annotation.Autowired
    private com.azhukov.agent.api.CronJobController cronController;

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void controllerReportsCommittedSchedulerRejection() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "properties");
        boolean previous = properties.getCron().isEnabled();
        var scheduler = org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduler");
        var rejecting = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        org.mockito.Mockito.when(rejecting.schedule(org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new java.util.concurrent.RejectedExecutionException("scheduler unavailable"));
        String owner = "http-status-" + java.util.UUID.randomUUID();
        var id = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        try {
            properties.getCron().setEnabled(true);
            org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", rejecting);
            com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
            var result = cronController.acceptSuggestion(id, null);
            assertThat(result).containsEntry("accepted", true).containsEntry("schedulingStatus", "failed");
            assertThat(cronJobRepository.findById(java.util.UUID.fromString((String) result.get("jobId"))).orElseThrow().getLastStatus()).isEqualTo("error");
        } finally {
            com.azhukov.agent.core.security.UserContext.clear();
            org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", scheduler);
            properties.getCron().setEnabled(previous);
            tx.executeWithoutResult(s -> { cronJobRepository.deleteAll(cronJobRepository.findAll().stream().filter(j -> owner.equals(j.getUserId())).toList()); cronSuggestionRepository.deleteById(id); });
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void schedulerFailurePersistsAndReportsErrorWithoutPretendingRollback() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "properties");
        boolean previous = properties.getCron().isEnabled();
        var scheduler = org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduler");
        var rejecting = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        org.mockito.Mockito.when(rejecting.schedule(org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new java.util.concurrent.RejectedExecutionException("scheduler unavailable"));
        String owner = "scheduler-failure-" + java.util.UUID.randomUUID();
        var id = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        try {
            properties.getCron().setEnabled(true);
            org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", rejecting);
            com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
            var accepted = suggestionService.acceptSuggestion(id);
            assertThat(accepted).isNotNull();
            var persisted = cronJobRepository.findById(accepted.getId()).orElseThrow();
            assertThat(persisted.getLastStatus()).isEqualTo("error");
            assertThat(persisted.getLastError()).contains("schedule");
            assertThat(persisted.getNextRunAt()).isNull();
            assertThat(cronSuggestionRepository.findById(id).orElseThrow().getStatus()).isEqualTo("accepted");
            assertThat(suggestionService.acceptSuggestion(id)).isNull();
            org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", scheduler);
            cronJobService.init();
            var recovered = cronJobRepository.findById(accepted.getId()).orElseThrow();
            assertThat(recovered.getNextRunAt()).isNotNull();
            assertThat(recovered.getLastStatus()).isNull();
            @SuppressWarnings("unchecked")
            var tasks = (java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>>) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduledTasks");
            var future = tasks.remove(accepted.getId());
            assertThat((Object) future).isNotNull();
            future.cancel(false);
        } finally {
            com.azhukov.agent.core.security.UserContext.clear();
            org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", scheduler);
            properties.getCron().setEnabled(previous);
            tx.executeWithoutResult(s -> { cronJobRepository.deleteAll(cronJobRepository.findAll().stream().filter(j -> owner.equals(j.getUserId())).toList()); cronSuggestionRepository.deleteById(id); });
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void rolledBackResumeDoesNotLeaveScheduledFuture() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "properties");
        boolean previous = properties.getCron().isEnabled();
        String owner = "resume-rollback-" + java.util.UUID.randomUUID();
        var suggestionId = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        java.util.UUID jobId = null;
        try {
            properties.getCron().setEnabled(false);
            com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
            jobId = suggestionService.acceptSuggestion(suggestionId).getId();
            cronJobService.pause(jobId);
            properties.getCron().setEnabled(true);
            var selected = jobId;
            tx.executeWithoutResult(status -> { cronJobService.resume(selected); status.setRollbackOnly(); });
            assertThat(cronJobRepository.findById(jobId).orElseThrow().isEnabled()).isFalse();
            @SuppressWarnings("unchecked")
            var tasks = (java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>>) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduledTasks");
            assertThat(tasks).doesNotContainKey(jobId);
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
            tx.executeWithoutResult(s -> cronSuggestionRepository.deleteById(suggestionId));
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void twoResumesInOneTransactionLeaveOnlyOneLiveFuture() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "properties");
        boolean previous = properties.getCron().isEnabled();
        String owner = "resume-repeat-" + java.util.UUID.randomUUID();
        var suggestionId = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        var oldScheduler = org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduler");
        var recordingScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        var scheduled = new java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.ScheduledFuture<?>>();
        var wrapper = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            var future = recordingScheduler.schedule(inv.getArgument(0, Runnable.class), inv.getArgument(1, Long.class), inv.getArgument(2, java.util.concurrent.TimeUnit.class));
            scheduled.add(future);
            return future;
        }).when(wrapper).schedule(org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        java.util.UUID jobId = null;
        try {
            properties.getCron().setEnabled(false);
            com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
            jobId = suggestionService.acceptSuggestion(suggestionId).getId();
            cronJobService.pause(jobId);
            org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", wrapper);
            properties.getCron().setEnabled(true);
            var selected = jobId;
            tx.executeWithoutResult(status -> { cronJobService.resume(selected); cronJobService.resume(selected); });
            assertThat(scheduled.stream().filter(f -> !f.isCancelled() && !f.isDone()).count()).isEqualTo(1);
        } finally {
            com.azhukov.agent.core.security.UserContext.clear();
            org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", oldScheduler);
            properties.getCron().setEnabled(previous);
            scheduled.forEach(f -> f.cancel(false));
            recordingScheduler.shutdownNow();
            @SuppressWarnings("unchecked")
            var tasks = (java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>>) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduledTasks");
            if (jobId != null) {
                tasks.remove(jobId);
                var cleanupJobId = jobId;
                tx.executeWithoutResult(s -> cronJobRepository.deleteById(cleanupJobId));
            }
            tx.executeWithoutResult(s -> cronSuggestionRepository.deleteById(suggestionId));
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void rolledBackRemoveKeepsExistingSchedule() {
        withScheduledJob((tx, jobId, tasks, original) -> {
            tx.executeWithoutResult(status -> { cronJobService.remove(jobId); status.setRollbackOnly(); });
            assertThat(cronJobRepository.findById(jobId)).isPresent();
            assertThat((Object) tasks.get(jobId)).isSameAs(original);
            assertThat(original.isCancelled()).isFalse();
        });
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void committedRemoveCancelsExistingSchedule() {
        withScheduledJob((tx, jobId, tasks, original) -> {
            tx.executeWithoutResult(status -> cronJobService.remove(jobId));
            assertThat(cronJobRepository.findById(jobId)).isEmpty();
            assertThat(tasks).doesNotContainKey(jobId);
            assertThat(original.isCancelled()).isTrue();
        });
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void rolledBackUpdateKeepsOriginalSchedule() {
        withScheduledJob((tx, jobId, tasks, original) -> {
            tx.executeWithoutResult(status -> { cronJobService.update(jobId, "updated", null, null, null, null); status.setRollbackOnly(); });
            assertThat(cronJobRepository.findById(jobId).orElseThrow().getName()).isNotEqualTo("updated");
            assertThat((Object) tasks.get(jobId)).isSameAs(original);
            assertThat(original.isCancelled()).isFalse();
        });
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void committedUpdateReplacesOriginalSchedule() {
        withScheduledJob((tx, jobId, tasks, original) -> {
            tx.executeWithoutResult(status -> cronJobService.update(jobId, "updated", null, null, null, null));
            assertThat(cronJobRepository.findById(jobId).orElseThrow().getName()).isEqualTo("updated");
            assertThat((Object) tasks.get(jobId)).isNotNull().isNotSameAs(original);
            assertThat(original.isCancelled()).isTrue();
        });
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void rolledBackPauseKeepsExistingSchedule() {
        withScheduledJob((tx, jobId, tasks, original) -> {
            tx.executeWithoutResult(status -> { cronJobService.pause(jobId); status.setRollbackOnly(); });
            assertThat(cronJobRepository.findById(jobId).orElseThrow().isEnabled()).isTrue();
            assertThat((Object) tasks.get(jobId)).isSameAs(original);
            assertThat(original.isCancelled()).isFalse();
        });
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void committedPauseCancelsExistingSchedule() {
        withScheduledJob((tx, jobId, tasks, original) -> {
            tx.executeWithoutResult(status -> cronJobService.pause(jobId));
            assertThat(cronJobRepository.findById(jobId).orElseThrow().isEnabled()).isFalse();
            assertThat(tasks).doesNotContainKey(jobId);
            assertThat(original.isCancelled()).isTrue();
        });
    }

    @FunctionalInterface
    private interface ScheduleScenario {
        void check(org.springframework.transaction.support.TransactionTemplate tx, java.util.UUID jobId,
                   java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>> tasks,
                   java.util.concurrent.ScheduledFuture<?> original);
    }

    private void withScheduledJob(ScheduleScenario scenario) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils
            .getField(cronJobService, "properties");
        boolean previous = properties.getCron().isEnabled();
        String owner = "schedule-fixture-" + java.util.UUID.randomUUID();
        var suggestionId = tx.execute(status -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        @SuppressWarnings("unchecked")
        var tasks = (java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>>) org.springframework.test.util.ReflectionTestUtils
            .getField(cronJobService, "scheduledTasks");
        java.util.UUID jobId = null;
        try {
            properties.getCron().setEnabled(true);
            com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
            jobId = suggestionService.acceptSuggestion(suggestionId).getId();
            var original = tasks.get(jobId);
            assertThat((Object) original).isNotNull();
            scenario.check(tx, jobId, tasks, original);
        } finally {
            com.azhukov.agent.core.security.UserContext.clear();
            properties.getCron().setEnabled(previous);
            var selected = jobId;
            if (selected != null) {
                var future = tasks.remove(selected);
                if (future != null) future.cancel(false);
                tx.executeWithoutResult(status -> cronJobRepository.deleteById(selected));
            }
            tx.executeWithoutResult(status -> cronSuggestionRepository.deleteById(suggestionId));
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void createdThenPausedInOneTransactionDoesNotSchedule() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "properties");
        boolean previous = properties.getCron().isEnabled();
        String owner = "accept-pause-" + java.util.UUID.randomUUID();
        var suggestionId = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        java.util.UUID jobId = null;
        try {
            properties.getCron().setEnabled(true);
            com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
            jobId = tx.execute(status -> {
                var created = suggestionService.acceptSuggestion(suggestionId);
                cronJobService.pause(created.getId());
                return created.getId();
            });
            assertThat(cronJobRepository.findById(jobId).orElseThrow().isEnabled()).isFalse();
            @SuppressWarnings("unchecked")
            var tasks = (java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>>) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduledTasks");
            assertThat(tasks).doesNotContainKey(jobId);
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
            tx.executeWithoutResult(s -> cronSuggestionRepository.deleteById(suggestionId));
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void committedResumeSchedulesJob() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "properties");
        boolean previous = properties.getCron().isEnabled();
        String owner = "resume-commit-" + java.util.UUID.randomUUID();
        var suggestionId = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(owner, "key")).getId());
        java.util.UUID jobId = null;
        try {
            properties.getCron().setEnabled(false);
            com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
            jobId = suggestionService.acceptSuggestion(suggestionId).getId();
            cronJobService.pause(jobId);
            properties.getCron().setEnabled(true);
            var selected = jobId;
            tx.executeWithoutResult(status -> cronJobService.resume(selected));
            assertThat(cronJobRepository.findById(jobId).orElseThrow().isEnabled()).isTrue();
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
            tx.executeWithoutResult(s -> cronSuggestionRepository.deleteById(suggestionId));
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
            jobId = suggestionService.acceptSuggestion(id).getId();
            assertThat(suggestionService.acceptSuggestion(id)).isNull();
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
            jobId = suggestionService.acceptSuggestion(id).getId();
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
        assertThat(cronSuggestionRepository.findByIdAndUserIdForUpdate(bob.getId(), "alice")).isEmpty();
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pause", "update", "remove", "update-paused"})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void completingRunDoesNotUndoCommittedChange(String change) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the run");
            }
            return null;
        }).when(runtime).runBackground(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.eq(true));
        var scheduler = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        var callbacks = new java.util.ArrayList<Runnable>();
        org.mockito.Mockito.doAnswer(call -> {
            callbacks.add(call.getArgument(0));
            return org.mockito.Mockito.mock(java.util.concurrent.ScheduledFuture.class);
        }).when(scheduler).schedule(org.mockito.ArgumentMatchers.any(Runnable.class),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class));
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils
            .getField(cronJobService, "properties");
        var originalScheduler = org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduler");
        boolean enabled = properties.getCron().isEnabled();
        properties.getCron().setEnabled(true);
        org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", scheduler);
        var job = cronJobService.create("schedule-race-" + java.util.UUID.randomUUID(), "every 1h", "work", "local");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            boolean manual = "update-paused".equals(change);
            if (manual) cronJobService.pause(job.getId());
            Runnable run = manual ? () -> cronJobService.runNow(job.getId()) : callbacks.getFirst();
            var running = executor.submit(run);
            assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            switch (change) {
                case "pause" -> assertThat(cronController.pause(job.getId()).enabled()).isFalse();
                case "update", "update-paused" -> assertThat(cronController.update(job.getId(),
                    new com.azhukov.agent.api.CronJobController.UpdateCronRequest(job.getName(), "every 2h",
                        null, null, null, null, null, null)).schedule()).isEqualTo("every 2h");
                case "remove" -> assertThat(cronController.delete(job.getId()).getStatusCode().is2xxSuccessful()).isTrue();
                default -> throw new AssertionError(change);
            }
            int scheduledAfterChange = callbacks.size();
            release.countDown();
            running.get(10, java.util.concurrent.TimeUnit.SECONDS);

            var persisted = cronJobRepository.findById(job.getId());
            switch (change) {
                case "pause" -> assertThat(persisted.orElseThrow().isEnabled()).isFalse();
                case "update", "update-paused" -> assertThat(persisted.orElseThrow().getSchedule()).isEqualTo("every 2h");
                case "remove" -> assertThat(persisted).isEmpty();
            }
            if (manual) assertThat(persisted.orElseThrow().getNextRunAt()).isNull();
            assertThat(callbacks).hasSize(scheduledAfterChange);
            persisted.ifPresent(current -> assertThat(current.getLastStatus()).isNull());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            cronJobService.remove(job.getId());
            properties.getCron().setEnabled(enabled);
            org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", originalScheduler);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void completedRunOnlyEnqueuesForItsCurrentDestination(boolean changeDestination) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("run not released");
            throw new IllegalStateException("provider failed");
        }).when(runtime).runBackground(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.eq(true));

        var job = cronJobService.create("delivery-revision-" + java.util.UUID.randomUUID(), "every 1h", "work", "telegram:701938011");
        cronJobService.pause(job.getId());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> cronJobService.runNow(job.getId()));
            try {
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                if (changeDestination) {
                    cronController.update(job.getId(), new com.azhukov.agent.api.CronJobController.UpdateCronRequest(
                        job.getName(), job.getSchedule(), null, "local", false, null, null, null));
                }
            } finally {
                release.countDown();
            }
            running.get(10, java.util.concurrent.TimeUnit.SECONDS);
            int history = jdbc.queryForObject("select count(*) from cron_execution_log where job_id = ? and status = 'failure'",
                Integer.class, job.getId());
            int deliveries = jdbc.queryForObject("""
                select count(*) from delivery_work_items d join cron_execution_log c on d.source_id = cast(c.id as text)
                where c.job_id = ? and d.source_type = 'cron_execution'
                """, Integer.class, job.getId());
            assertThat(history).isOne();
            assertThat(deliveries).isEqualTo(changeDestination ? 0 : 1);
        } finally {
            jdbc.update("""
                delete from delivery_work_items where source_type = 'cron_execution'
                and source_id in (select cast(id as text) from cron_execution_log where job_id = ?)
                """, job.getId());
            cronJobService.remove(job.getId());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void scheduleUpdatesCannotPublishAnOlderDueTime() throws Exception {
        var scheduler = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        var callbacks = new java.util.concurrent.CopyOnWriteArrayList<Runnable>();
        var olderReconcilerEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseOlderReconciler = new java.util.concurrent.CountDownLatch(1);
        var newerStarted = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            callbacks.add(call.getArgument(0));
            if (callbacks.size() == 2) {
                olderReconcilerEntered.countDown();
                if (!releaseOlderReconciler.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release reconciliation");
                }
            }
            return org.mockito.Mockito.mock(java.util.concurrent.ScheduledFuture.class);
        }).when(scheduler).schedule(org.mockito.ArgumentMatchers.any(Runnable.class),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class));
        var props = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils
            .getField(cronJobService, "properties");
        var previous = org.springframework.test.util.ReflectionTestUtils.getField(cronJobService, "scheduler");
        boolean wasEnabled = props.getCron().isEnabled();
        props.getCron().setEnabled(true);
        org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", scheduler);
        var job = cronJobService.create("review-race", "every 1h", "work", "local");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            try {
                var older = executor.submit(() -> cronJobService.update(job.getId(), null, "every 2h", null, null, null));
                assertThat(olderReconcilerEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var newer = executor.submit(() -> {
                    newerStarted.countDown();
                    cronJobService.update(job.getId(), null, "every 3h", null, null, null);
                });
                assertThat(newerStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> newer.get(250, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
                assertThat(cronJobRepository.findById(job.getId()).orElseThrow().getSchedule()).isEqualTo("every 1h");
                releaseOlderReconciler.countDown();
                older.get(10, java.util.concurrent.TimeUnit.SECONDS);
                newer.get(10, java.util.concurrent.TimeUnit.SECONDS);
                var actual = cronJobRepository.findById(job.getId()).orElseThrow();
                assertThat(actual.getSchedule()).isEqualTo("every 3h");
                assertThat(actual.getNextRunAt()).isAfter(Instant.now().plusSeconds(10_700));
                int count = callbacks.size();
                callbacks.get(1).run();
                assertThat(callbacks).hasSize(count);
            } finally {
                releaseOlderReconciler.countDown();
            }
        } finally {
            cronJobService.remove(job.getId());
            props.getCron().setEnabled(wasEnabled);
            org.springframework.test.util.ReflectionTestUtils.setField(cronJobService, "scheduler", previous);
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void delayedCommitCallbackCannotReplaceNewerTimer() throws Exception {
        var props = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils
            .getField(cronJobService, "properties");
        boolean enabled = props.getCron().isEnabled();
        props.getCron().setEnabled(true);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var olderCommitted = new java.util.concurrent.CountDownLatch(1);
        var releaseOlder = new java.util.concurrent.CountDownLatch(1);
        var job = cronJobService.create("publication-race", "every 1h", "work", "local");
        var timers = (java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils
            .getField(cronJobService, "scheduledTasks");
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try {
                var older = executor.submit(() -> tx.executeWithoutResult(status -> {
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int completionStatus) {
                                olderCommitted.countDown();
                                try {
                                    releaseOlder.await(10, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        });
                    cronJobService.update(job.getId(), null, "every 2h", null, null, null);
                }));
                assertThat(olderCommitted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                cronJobService.update(job.getId(), null, "every 3h", null, null, null);
                Object newerTimer = timers.get(job.getId());
                assertThat(newerTimer).isNotNull();
                releaseOlder.countDown();
                older.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(timers.get(job.getId())).isSameAs(newerTimer);
                assertThat(cronJobRepository.findById(job.getId()).orElseThrow().getSchedule()).isEqualTo("every 3h");
            } finally {
                releaseOlder.countDown();
            }
        } finally {
            cronJobService.remove(job.getId());
            props.getCron().setEnabled(enabled);
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void updateSchedulesWithOnlyOneConnectionAvailable() throws Exception {
        var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        var properties = (com.azhukov.agent.config.AgentProperties) org.springframework.test.util.ReflectionTestUtils
            .getField(cronJobService, "properties");
        boolean enabled = properties.getCron().isEnabled();
        long timeout = pool.getConnectionTimeout();
        var job = cronJobService.create("pool-test", "every 1h", "work", "local");
        var held = new java.util.ArrayList<java.sql.Connection>();
        try {
            for (int i = 1; i < pool.getMaximumPoolSize(); i++) held.add(dataSource.getConnection());
            pool.setConnectionTimeout(500);
            properties.getCron().setEnabled(true);

            var updated = cronJobService.update(job.getId(), null, "every 2h", null, null, null);
            assertThat(updated.getNextRunAt()).isAfter(Instant.now().plusSeconds(7100));
            assertThat(cronJobRepository.findById(job.getId()).orElseThrow().getNextRunAt())
                .isEqualTo(updated.getNextRunAt());
        } finally {
            pool.setConnectionTimeout(timeout);
            for (var connection : held) connection.close();
            properties.getCron().setEnabled(enabled);
            cronJobService.remove(job.getId());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void gatewayOwnerScopeIsRequiredAndIsolatesSuggestions() throws Exception {
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getSecurity().setApiKey("isolated-review-test-key");
        var users = org.mockito.Mockito.mock(com.azhukov.agent.service.UserAccessService.class);
        var filter = new com.azhukov.agent.api.filter.ApiKeyAuthFilter(props, users);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(cronController)
            .addFilters(filter).build();
        String first = "701938001";
        String second = "701938002";
        org.mockito.Mockito.when(users.authenticate("cli-user-key")).thenReturn(
            new com.azhukov.agent.service.UserAccessService.AuthenticatedUser(first, "user"));
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var id = tx.execute(s -> cronSuggestionRepository.saveAndFlush(newSuggestion(first, "gateway-owner")).getId());
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/agent/cron/suggestions")
                    .header("X-API-Key", "isolated-review-test-key"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/agent/cron/suggestions")
                    .header("X-API-Key", "cli-user-key"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(1));
            for (String owner : java.util.List.of(first, second)) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/agent/cron/suggestions")
                        .header("X-API-Key", "isolated-review-test-key").param("ownerId", owner))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()")
                        .value(owner.equals(first) ? 1 : 0));
            }
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/agent/cron/suggestions/{id}/dismiss", id)
                    .header("X-API-Key", "isolated-review-test-key").param("ownerId", second))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.dismissed").value(false));
        } finally {
            tx.executeWithoutResult(s -> cronSuggestionRepository.deleteById(id));
        }
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
