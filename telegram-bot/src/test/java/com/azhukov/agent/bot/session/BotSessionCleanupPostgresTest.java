package com.azhukov.agent.bot.session;

import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
class BotSessionCleanupPostgresTest {
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.boot.persistence.autoconfigure.EntityScan(basePackageClasses = BotSessionEntity.class)
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackageClasses = BotSessionRepository.class)
    @org.springframework.context.annotation.Import(BotSessionStore.class)
    static class TestApp {}

    static PostgreSQLContainer<?> postgres;
    ConfigurableApplicationContext context;
    BotSessionStore store;
    BotSessionRepository repository;

    @BeforeAll static void startPostgres() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        postgres.start();
    }
    @AfterAll static void stopPostgres() { postgres.stop(); }
    @BeforeEach void setup() { startContext(); }
    @AfterEach void shutdown() { if (context != null) context.close(); }

    void startContext() {
        context = new SpringApplicationBuilder(TestApp.class).web(WebApplicationType.NONE)
            .run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.flyway.locations=classpath:db/bot-migration",
                "--spring.flyway.table=flyway_bot_schema_history",
                "--spring.flyway.baseline-on-migrate=true", "--spring.flyway.baseline-version=0",
                "--spring.jpa.hibernate.ddl-auto=none", "--spring.main.banner-mode=off");
        store = context.getBean(BotSessionStore.class);
        repository = context.getBean(BotSessionRepository.class);
    }

    BotSessionEntity session(String owner) {
        BotSessionEntity row = new BotSessionEntity();
        row.setUserId(owner); row.setChatId(owner); row.setBackendSessionId(UUID.randomUUID());
        row.setCreatedAt(Instant.now()); row.setUpdatedAt(Instant.now());
        return repository.findById(repository.saveAndFlush(row).getId()).orElseThrow();
    }

    @Test void failedCleanupSurvivesContextRestartAndIsClearedOnSuccess() {
        var row = session("retry-" + UUID.randomUUID());
        assertThat(store.finalizeWithCleanupPending(row.getId(), row.getUpdatedAt())).isTrue();
        assertThat(store.retryCleanup(row.getId(), id -> false)).isFalse();
        context.close();
        startContext();
        assertThat(store.pendingCleanups()).extracting(BotSessionEntity::getId).contains(row.getId());
        assertThat(store.retryCleanup(row.getId(), id -> id.equals(row.getBackendSessionId()))).isTrue();
        assertThat(repository.findById(row.getId()).orElseThrow().isCleanupPending()).isFalse();
        assertThat(store.retryCleanup(row.getId(), id -> { throw new AssertionError("duplicate cleanup"); })).isFalse();
    }

    @Test void upgradeFromV10PreservesExistingRowsAndAppliesPendingDefaults() throws Exception {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try {
            var before = org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/bot-migration").table("flyway_bot_schema_history").target("10").load();
            before.migrate();
            UUID id = UUID.randomUUID();
            try (var c = ds.getConnection(); var statement = c.createStatement()) {
                statement.execute("INSERT INTO " + schema + ".bot_sessions(id,user_id,chat_id,active) VALUES ('" + id + "','upgrade-owner','123',true)");
            }
            var after = org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/bot-migration").table("flyway_bot_schema_history").load();
            assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
            after.validate();
            try (var c = ds.getConnection(); var statement = c.createStatement();
                 var rs = statement.executeQuery("SELECT user_id,active,cleanup_pending,cleanup_on_backend_binding FROM " + schema + ".bot_sessions WHERE id='" + id + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("upgrade-owner");
                assertThat(rs.getBoolean(2)).isTrue();
                assertThat(rs.getBoolean(3)).isFalse();
                assertThat(rs.getBoolean(4)).isFalse();
                assertThat(rs.next()).isFalse();
            }
        } finally {
            try (var c = ds.getConnection(); var statement = c.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test void repeatedIdenticalBackendBindingAfterCleanupDoesNotScheduleDuplicate() {
        var row = session("same-binding-" + UUID.randomUUID());
        store.deactivateAllWithCleanupPending(row.getUserId());
        assertThat(store.retryCleanup(row.getId(), id -> true)).isTrue();
        store.updateBackendSessionId(row.getId(), row.getBackendSessionId());
        assertThat(repository.findById(row.getId()).orElseThrow().isCleanupPending()).isFalse();
    }

    @Test void resumedSessionAcceptsLateBackendBindingWithoutCleanup() {
        var row = new BotSessionEntity();
        row.setUserId("resumed-late-" + UUID.randomUUID()); row.setChatId(row.getUserId());
        row.setCreatedAt(Instant.now()); row.setUpdatedAt(Instant.now());
        row = repository.saveAndFlush(row);
        store.deactivateAllWithCleanupPending(row.getUserId());
        assertThat(store.resumeSession(row.getId(), row.getUserId()).isActive()).isTrue();
        store.updateBackendSessionId(row.getId(), UUID.randomUUID());
        var saved = repository.findById(row.getId()).orElseThrow();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.isCleanupPending()).isFalse();
        assertThat(saved.isCleanupOnBackendBinding()).isFalse();
    }

    @Test void lateBackendIdOnTopicSwitchMustNotCleanupResumableSession() {
        var row = new BotSessionEntity();
        row.setUserId("topic-switch-" + UUID.randomUUID()); row.setChatId(row.getUserId());
        row.setCreatedAt(Instant.now()); row.setUpdatedAt(Instant.now());
        row = repository.saveAndFlush(row);
        store.createFreshSession(row.getUserId(), row.getChatId(), null);
        store.updateBackendSessionId(row.getId(), UUID.randomUUID());
        var saved = repository.findById(row.getId()).orElseThrow();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.isCleanupPending()).as("switching topics does not end the old session").isFalse();
    }

    @Test void backendIdArrivingAfterNewMustRemainEligibleForCleanup() {
        var row = new BotSessionEntity();
        row.setUserId("late-id-" + UUID.randomUUID()); row.setChatId(row.getUserId());
        row.setCreatedAt(Instant.now()); row.setUpdatedAt(Instant.now());
        row = repository.saveAndFlush(row);
        store.deactivateAllWithCleanupPending(row.getUserId());
        var backendId = UUID.randomUUID();
        store.updateBackendSessionId(row.getId(), backendId);
        var saved = repository.findById(row.getId()).orElseThrow();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getBackendSessionId()).isEqualTo(backendId);
        assertThat(saved.isCleanupPending()).as("late backend binding must remain discoverable for cleanup").isTrue();
    }

    @Test void exceptionalBackendCleanupRollsBackPendingAndAllowsRetry() {
        var row = session("exception-retry-" + UUID.randomUUID());
        store.deactivateAllWithCleanupPending(row.getUserId());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.retryCleanup(row.getId(), id -> {
            throw new IllegalStateException("synthetic backend failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(row.getId()).orElseThrow().isCleanupPending()).isTrue();
        assertThat(store.retryCleanup(row.getId(), id -> true)).isTrue();
        assertThat(repository.findById(row.getId()).orElseThrow().isCleanupPending()).isFalse();
    }

    @Test void realHttpClientRetriesBackendRefusalAndPersistsOnlySuccess() throws Exception {
        var row = session("http-retry-" + UUID.randomUUID());
        store.deactivateAllWithCleanupPending(row.getUserId());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agent/session/" + row.getBackendSessionId() + "/cleanup", exchange -> {
            int status = calls.incrementAndGet() == 1 ? 500 : 200;
            exchange.sendResponseHeaders(status, -1); exchange.close();
        });
        server.start();
        try {
            var client = new com.azhukov.agent.bot.core.SessionApiClient(
                org.springframework.web.client.RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
                new com.fasterxml.jackson.databind.ObjectMapper());
            assertThat(store.retryCleanup(row.getId(), id -> client.cleanupSession(id.toString()))).isFalse();
            assertThat(repository.findById(row.getId()).orElseThrow().isCleanupPending()).isTrue();
            assertThat(store.retryCleanup(row.getId(), id -> client.cleanupSession(id.toString()))).isTrue();
            assertThat(repository.findById(row.getId()).orElseThrow().isCleanupPending()).isFalse();
            assertThat(calls.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @Test void newCommandPersistsFailedCleanupAndRetriesAfterContextRestart() {
        var row = session("new-" + UUID.randomUUID());
        var backend = org.mockito.Mockito.mock(com.azhukov.agent.bot.core.AgentBackendClient.class);
        org.mockito.Mockito.when(backend.cleanupSession(row.getBackendSessionId().toString())).thenReturn(false, true);
        var command = new com.azhukov.agent.bot.commands.impl.NewSessionCommand(store, backend);
        assertThat(command.handle(null, row)).contains("cleanup failed");
        var afterNew = repository.findById(row.getId()).orElseThrow();
        assertThat(afterNew.isActive()).isFalse();
        assertThat(afterNew.isCleanupPending()).isTrue();
        context.close();
        startContext();
        assertThat(store.pendingCleanups()).extracting(BotSessionEntity::getId).contains(row.getId());
        assertThat(store.retryCleanup(row.getId(), id -> backend.cleanupSession(id.toString()))).isTrue();
        org.mockito.Mockito.verify(backend, org.mockito.Mockito.times(2)).cleanupSession(row.getBackendSessionId().toString());
        org.mockito.Mockito.verify(backend, org.mockito.Mockito.never()).resetSession(org.mockito.ArgumentMatchers.anyString());
        assertThat(repository.findById(row.getId()).orElseThrow().isCleanupPending()).isFalse();
    }

    @Test void staleMetadataWriteMustNotReactivateNewSessionOrLoseCleanup() throws Exception {
        var row = session("stale-write-" + UUID.randomUUID());
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var loaded = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(() -> tx.executeWithoutResult(status -> {
                var stale = repository.findById(row.getId()).orElseThrow();
                loaded.countDown();
                try { if (!proceed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                stale.setTitle("updated");
                repository.save(stale);
            }));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            store.deactivateAllWithCleanupPending(row.getUserId());
            proceed.countDown();
            writer.get(10, TimeUnit.SECONDS);
            var persisted = repository.findById(row.getId()).orElseThrow();
            assertThat(persisted.isActive()).as("stale metadata update must not reactivate session").isFalse();
            assertThat(persisted.isCleanupPending()).as("stale metadata update must not erase cleanup").isTrue();
        } finally { proceed.countDown(); executor.shutdownNow(); }
    }

    @Test void staleMetadataUpdateCannotUndoResumeCleanupCancellation() throws Exception {
        var row = session("stale-resume-" + UUID.randomUUID());
        assertThat(store.finalizeWithCleanupPending(row.getId(), row.getUpdatedAt())).isTrue();
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var loaded = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(() -> tx.executeWithoutResult(status -> {
                var stale = repository.findById(row.getId()).orElseThrow();
                loaded.countDown();
                try { if (!proceed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                stale.setTitle("later title"); repository.save(stale);
            }));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.resumeSession(row.getId(), row.getUserId()).isActive()).isTrue();
            proceed.countDown(); writer.get(10, TimeUnit.SECONDS);
            var saved = repository.findById(row.getId()).orElseThrow();
            assertThat(saved.isActive()).isTrue();
            assertThat(saved.isCleanupPending()).isFalse();
            assertThat(saved.isCleanupOnBackendBinding()).isFalse();
            assertThat(saved.getTitle()).isEqualTo("later title");
        } finally { proceed.countDown(); executor.shutdownNow(); }
    }

    @Test void unrelatedMetadataAndCleanupCommitInEitherOrderWithoutLosingState() throws Exception {
        var row = session("write-first-" + UUID.randomUUID());
        var tx = new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var loaded = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var finalizer = executor.submit(() -> tx.executeWithoutResult(status -> {
                var stale = repository.findById(row.getId()).orElseThrow();
                loaded.countDown();
                try { if (!proceed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                stale.setActive(false); stale.setCleanupPending(true);
                repository.save(stale);
            }));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            store.updateTitle(row.getId(), "new title");
            proceed.countDown();
            finalizer.get(10, TimeUnit.SECONDS);
            var persisted = repository.findById(row.getId()).orElseThrow();
            assertThat(persisted.getTitle()).isEqualTo("new title");
            assertThat(persisted.isActive()).isFalse();
            assertThat(persisted.isCleanupPending()).isTrue();
        } finally { proceed.countDown(); executor.shutdownNow(); }
    }

    @Test void watcherRetriesDurableCleanupAfterContextRestart() {
        var row = session("watcher-" + UUID.randomUUID());
        assertThat(store.finalizeWithCleanupPending(row.getId(), row.getUpdatedAt())).isTrue();
        context.close();
        startContext();
        var backend = org.mockito.Mockito.mock(com.azhukov.agent.bot.core.AgentBackendClient.class);
        org.mockito.Mockito.when(backend.cleanupSession(row.getBackendSessionId().toString())).thenReturn(false, true);
        var policy = new SessionResetPolicy();
        var watcher = new SessionExpiryWatcher(store, policy, backend, org.mockito.Mockito.mock(BusySessionHandler.class));
        try {
            watcher.checkExpiredSessions();
            assertThat(repository.findById(row.getId()).orElseThrow().isCleanupPending()).isTrue();
            watcher.checkExpiredSessions();
            assertThat(repository.findById(row.getId()).orElseThrow().isCleanupPending()).isFalse();
            watcher.checkExpiredSessions();
            org.mockito.Mockito.verify(backend, org.mockito.Mockito.times(2)).cleanupSession(row.getBackendSessionId().toString());
            org.mockito.Mockito.verify(backend, org.mockito.Mockito.never()).resetSession(org.mockito.ArgumentMatchers.anyString());
        } finally { watcher.stop(); }
    }

    @Test void resumingBeforeRetryCancelsPendingAndPreventsBackendCall() {
        var row = session("resume-first-" + UUID.randomUUID());
        assertThat(store.finalizeWithCleanupPending(row.getId(), row.getUpdatedAt())).isTrue();
        assertThat(store.resumeSession(row.getId(), row.getUserId()).isActive()).isTrue();
        var called = new AtomicBoolean();
        assertThat(store.retryCleanup(row.getId(), id -> { called.set(true); return true; })).isFalse();
        assertThat(called).isFalse();
        assertThat(repository.findById(row.getId()).orElseThrow().isCleanupPending()).isFalse();
    }

    @Test void newCommandMarksEveryActiveThreadForRetry() {
        String owner = "threads-" + UUID.randomUUID();
        var first = session(owner);
        var second = new BotSessionEntity();
        second.setUserId(owner); second.setChatId(owner); second.setThreadId(42L);
        second.setBackendSessionId(UUID.randomUUID());
        second.setCreatedAt(Instant.now()); second.setUpdatedAt(Instant.now());
        second = repository.saveAndFlush(second);
        var backend = org.mockito.Mockito.mock(com.azhukov.agent.bot.core.AgentBackendClient.class);
        org.mockito.Mockito.when(backend.cleanupSession(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        new com.azhukov.agent.bot.commands.impl.NewSessionCommand(store, backend).handle(null, first);
        assertThat(store.pendingCleanups()).extracting(BotSessionEntity::getId).contains(first.getId(), second.getId());
        assertThat(store.retryCleanup(second.getId(), id -> true)).isTrue();
        assertThat(repository.findById(second.getId()).orElseThrow().isCleanupPending()).isFalse();
        assertThat(repository.findById(first.getId()).orElseThrow().isCleanupPending()).isTrue();
    }

    @Test void foreignResumeCannotCancelPendingCleanup() {
        var row = session("owned-" + UUID.randomUUID());
        assertThat(store.finalizeWithCleanupPending(row.getId(), row.getUpdatedAt())).isTrue();
        assertThat(store.resumeSession(row.getId(), "other-owner")).isNull();
        var unchanged = repository.findById(row.getId()).orElseThrow();
        assertThat(unchanged.isActive()).isFalse();
        assertThat(unchanged.isCleanupPending()).isTrue();
    }

    @Test void staleExpiryDecisionMustNotDeactivateResumedSession() {
        var row = session("stale-" + UUID.randomUUID());
        assertThat(store.finalizeWithCleanupPending(row.getId(), row.getUpdatedAt())).isTrue();
        assertThat(store.resumeSession(row.getId(), row.getUserId()).isActive()).isTrue();
        assertThat(store.finalizeWithCleanupPending(row.getId(), row.getUpdatedAt())).isFalse();
        assertThat(repository.findById(row.getId()).orElseThrow().isActive())
            .as("a stale expiry decision must not deactivate a resumed session").isTrue();
    }

    @Test void resumeWaitsForCleanupAndActiveSessionIsNotCleanedAgain() throws Exception {
        var row = session("race-" + UUID.randomUUID());
        assertThat(store.finalizeWithCleanupPending(row.getId(), row.getUpdatedAt())).isTrue();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var cleanup = executor.submit(() -> store.retryCleanup(row.getId(), id -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return true;
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var resume = executor.submit(() -> store.resumeSession(row.getId(), row.getUserId()));
            try { resume.get(300, TimeUnit.MILLISECONDS); throw new AssertionError("resume did not wait"); }
            catch (TimeoutException expected) { /* row lock must serialize the two operations */ }
            release.countDown();
            assertThat(cleanup.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(resume.get(10, TimeUnit.SECONDS).isActive()).isTrue();
            var called = new AtomicBoolean();
            assertThat(store.retryCleanup(row.getId(), id -> { called.set(true); return true; })).isFalse();
            assertThat(called).isFalse();
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
