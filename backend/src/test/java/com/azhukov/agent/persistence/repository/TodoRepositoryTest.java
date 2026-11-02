package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@Transactional
class TodoRepositoryTest extends PostgresTestContainer {

    private static final String USER_ID_1 = "11111111-1111-1111-1111-111111111111";
    private static final String USER_ID_2 = "22222222-2222-2222-2222-222222222222";
    private static final String UNKNOWN_USER_ID = "99999999-9999-9999-9999-999999999999";

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";

    private static final String PRIORITY_HIGH = "high";
    private static final String PRIORITY_LOW = "low";

    private static final String TITLE_BUY_MILK = "Buy milk";
    private static final String TITLE_WALK_DOG = "Walk the dog";
    private static final String TITLE_READ_BOOK = "Read a book";
    private static final String TITLE_PAY_BILLS = "Pay bills";

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T12:00:01Z");
    private static final Instant T3 = Instant.parse("2026-01-01T12:00:02Z");
    private static final Instant T4 = Instant.parse("2026-01-01T12:00:03Z");

    private static final String MODEL_PROVIDER = "openai-compatible";
    private static final String MODEL_NAME = "kimi-k2.6";

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private com.azhukov.agent.tools.memory.TodoTool todoTool;

    @Autowired
    private com.azhukov.agent.core.tool.SpringToolRegistry registry;

    @Autowired
    private com.azhukov.agent.service.TodoService todoService;

    @Autowired
    private com.azhukov.agent.service.SessionQueryService sessionQueryService;

    private void assertDatabaseBlocked(int waiterPid, int holderPid) throws InterruptedException {
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        boolean blocked = false;
        while (System.nanoTime() < deadline) {
            blocked = Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))", Boolean.class, holderPid, waiterPid));
            if (blocked) break;
            Thread.sleep(25);
        }
        assertThat(blocked).as("session deletion waits for todo transaction in PostgreSQL").isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void deletingSessionDuringMarkDone(boolean scoped) throws Exception {
        String user = "done-cascade-" + UUID.randomUUID();
        var saved = sessionRepository.saveAndFlush(newSession(user, "Done cascade"));
        var original = todoRepository.saveAndFlush(newTodo(saved.getId(), user, "original", STATUS_PENDING, PRIORITY_HIGH, T1));
        var found = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holderPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterReady = new java.util.concurrent.CountDownLatch(1);
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var delegate = org.mockito.Mockito.mock(TodoRepository.class, invocation -> {
            try {
                var result = invocation.getMethod().invoke(todoRepository, invocation.getArguments());
                if (invocation.getMethod().getName().equals("findOwnedForUpdate")) {
                    found.countDown();
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                }
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        var service = new com.azhukov.agent.service.TodoService(delegate);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var done = pool.submit(() -> transactions.execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return scoped ? service.markDoneForUser(original.getId(), user) : service.markDone(original.getId());
            }));
            assertThat(found.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var deletion = pool.submit(() -> transactions.execute(status -> {
                waiterPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                waiterReady.countDown();
                boolean removed = sessionQueryService.deleteSession(saved.getId());
                sessionRepository.flush();
                return removed;
            }));
            assertThat(waiterReady.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertDatabaseBlocked(waiterPid.get(), holderPid.get());
            release.countDown();
            assertThat(done.get(10, java.util.concurrent.TimeUnit.SECONDS)).isPresent();
            assertThat(deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(todoRepository.findByUserId(user)).isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            todoRepository.deleteByUserId(user); sessionRepository.deleteById(saved.getId());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void deletingSessionDuringMergeOfItsTodo(boolean numericId) throws Exception {
        String user = "merge-cascade-" + UUID.randomUUID();
        var oldSession = sessionRepository.saveAndFlush(newSession(user, "Old"));
        var newSession = sessionRepository.saveAndFlush(newSession(user, "New"));
        var original = todoRepository.saveAndFlush(newTodo(oldSession.getId(), user, "old", STATUS_PENDING, PRIORITY_HIGH, T1));
        var session = new com.azhukov.agent.core.model.Session(newSession.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        var found = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holderPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterReady = new java.util.concurrent.CountDownLatch(1);
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var delegate = org.mockito.Mockito.mock(TodoRepository.class, invocation -> {
            try {
                var result = invocation.getMethod().invoke(todoRepository, invocation.getArguments());
                if (invocation.getMethod().getName().equals("findOwnedForUpdate")) {
                    found.countDown();
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                }
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        var tool = new com.azhukov.agent.tools.memory.TodoTool(delegate);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var merging = pool.submit(() -> transactions.execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                String id = numericId ? "1" : original.getId().toString();
                return tool.execute("{\"merge\":true,\"todos\":[{\"id\":\"" + id + "\",\"content\":\"updated\"}]}", null, session);
            }));
            assertThat(found.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var deletion = pool.submit(() -> transactions.execute(status -> {
                waiterPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                waiterReady.countDown();
                boolean removed = sessionQueryService.deleteSession(oldSession.getId());
                sessionRepository.flush();
                return removed;
            }));
            assertThat(waiterReady.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertDatabaseBlocked(waiterPid.get(), holderPid.get());
            release.countDown();
            assertThat(merging.get(10, java.util.concurrent.TimeUnit.SECONDS).success()).isTrue();
            assertThat(deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(todoRepository.findByUserId(user)).isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            todoRepository.deleteByUserId(user); sessionRepository.deleteById(oldSession.getId()); sessionRepository.deleteById(newSession.getId());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void mergeAcrossSessionsWhenSecondSessionIsDeletedDoesNotDeadlock() throws Exception {
        String user = "second-session-" + UUID.randomUUID();
        var firstSession = sessionRepository.saveAndFlush(newSession(user, "First"));
        var secondSession = sessionRepository.saveAndFlush(newSession(user, "Second"));
        var first = todoRepository.saveAndFlush(newTodo(firstSession.getId(), user, "first", STATUS_PENDING, PRIORITY_HIGH, T1));
        var second = todoRepository.saveAndFlush(newTodo(secondSession.getId(), user, "second", STATUS_PENDING, PRIORITY_HIGH, T2));
        var session = new com.azhukov.agent.core.model.Session(firstSession.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        var firstLocked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var deleted = new java.util.concurrent.CountDownLatch(1);
        var finishDeletion = new java.util.concurrent.CountDownLatch(1);
        var delegate = org.mockito.Mockito.mock(TodoRepository.class, invocation -> {
            try {
                var result = invocation.getMethod().invoke(todoRepository, invocation.getArguments());
                if (invocation.getMethod().getName().equals("findOwnedForUpdate") && first.getId().equals(invocation.getArgument(0))) {
                    firstLocked.countDown();
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                }
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        var tool = new com.azhukov.agent.tools.memory.TodoTool(delegate);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var merge = pool.submit(() -> transactions.execute(status -> tool.execute("{\"merge\":true,\"todos\":[{\"id\":\"" + first.getId() + "\",\"content\":\"first updated\"},{\"id\":\"" + second.getId() + "\",\"content\":\"second updated\"}]}", null, session)));
            assertThat(firstLocked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var deletion = pool.submit(() -> transactions.execute(status -> {
                boolean removed = sessionQueryService.deleteSession(secondSession.getId());
                sessionRepository.flush();
                deleted.countDown();
                try { if (!finishDeletion.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return removed;
            }));
            assertThat(deleted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            finishDeletion.countDown();
            assertThat(deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(merge.get(10, java.util.concurrent.TimeUnit.SECONDS).success()).isTrue();
            assertThat(todoRepository.findByUserId(user)).extracting(TodoEntity::getTitle).containsExactlyInAnyOrder("first updated", "second updated");
        } finally {
            release.countDown(); finishDeletion.countDown(); pool.shutdownNow(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            todoRepository.deleteByUserId(user); sessionRepository.deleteById(firstSession.getId()); sessionRepository.deleteById(secondSession.getId());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void mergeAcrossSessionsDoesNotDeadlockWithConcurrentSessionDeletion() throws Exception {
        String user = "multi-session-" + UUID.randomUUID();
        var firstSession = sessionRepository.saveAndFlush(newSession(user, "First"));
        var secondSession = sessionRepository.saveAndFlush(newSession(user, "Second"));
        var first = todoRepository.saveAndFlush(newTodo(firstSession.getId(), user, "first", STATUS_PENDING, PRIORITY_HIGH, T1));
        var second = todoRepository.saveAndFlush(newTodo(secondSession.getId(), user, "second", STATUS_PENDING, PRIORITY_HIGH, T2));
        var session = new com.azhukov.agent.core.model.Session(secondSession.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        var firstLocked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holderPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterPid = new java.util.concurrent.atomic.AtomicInteger();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var delegate = org.mockito.Mockito.mock(TodoRepository.class, invocation -> {
            try {
                var result = invocation.getMethod().invoke(todoRepository, invocation.getArguments());
                if (invocation.getMethod().getName().equals("findOwnedForUpdate") && first.getId().equals(invocation.getArgument(0))) {
                    firstLocked.countDown();
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                }
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        var tool = new com.azhukov.agent.tools.memory.TodoTool(delegate);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var merge = pool.submit(() -> transactions.execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return tool.execute("{\"merge\":true,\"todos\":[{\"id\":\"" + first.getId() + "\",\"content\":\"first updated\"},{\"id\":\"" + second.getId() + "\",\"content\":\"second updated\"}]}", null, session);
            }));
            assertThat(firstLocked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var deletion = pool.submit(() -> transactions.execute(status -> {
                waiterPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                ready.countDown();
                boolean removed = sessionQueryService.deleteSession(firstSession.getId());
                sessionRepository.flush();
                return removed;
            }));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertDatabaseBlocked(waiterPid.get(), holderPid.get());
            release.countDown();
            assertThat(merge.get(10, java.util.concurrent.TimeUnit.SECONDS).success()).isTrue();
            assertThat(deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(todoRepository.findByUserId(user)).extracting(TodoEntity::getTitle).containsExactly("second updated");
        } finally {
            release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            todoRepository.deleteByUserId(user); sessionRepository.deleteById(firstSession.getId()); sessionRepository.deleteById(secondSession.getId());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void mergeAndMarkDoneAfterConcurrentCascadeDoNotUpdateDeletedRow(boolean merge) throws Exception {
        String user = "cascade-first-" + UUID.randomUUID();
        var oldSession = sessionRepository.saveAndFlush(newSession(user, "Old"));
        var newSession = sessionRepository.saveAndFlush(newSession(user, "New"));
        var original = todoRepository.saveAndFlush(newTodo(oldSession.getId(), user, "old", STATUS_PENDING, PRIORITY_HIGH, T1));
        var session = new com.azhukov.agent.core.model.Session(newSession.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var ready = new java.util.concurrent.CountDownLatch(1);
        var holderPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterPid = new java.util.concurrent.atomic.AtomicInteger();
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var deletion = pool.submit(() -> transactions.execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                boolean removed = sessionQueryService.deleteSession(oldSession.getId());
                sessionRepository.flush();
                held.countDown();
                try { if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return removed;
            }));
            assertThat(held.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var operation = pool.submit(() -> transactions.execute(status -> {
                waiterPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                ready.countDown();
                if (merge) return todoTool.execute("{\"merge\":true,\"todos\":[{\"id\":\"" + original.getId() + "\",\"content\":\"new\"}]}", null, session).success();
                return todoService.markDoneForUser(original.getId(), user).isEmpty();
            }));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertDatabaseBlocked(waiterPid.get(), holderPid.get());
            release.countDown();
            assertThat(deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(operation.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var todos = todoRepository.findByUserId(user);
            if (merge) assertThat(todos).extracting(TodoEntity::getTitle).containsExactly("new");
            else assertThat(todos).isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            todoRepository.deleteByUserId(user); sessionRepository.deleteById(oldSession.getId()); sessionRepository.deleteById(newSession.getId());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void deletingOldSessionDuringReplacementKeepsNewSessionsTodo() throws Exception {
        String user = "session-cascade-" + UUID.randomUUID();
        var oldSession = sessionRepository.saveAndFlush(newSession(user, "Old"));
        var newSession = sessionRepository.saveAndFlush(newSession(user, "New"));
        todoRepository.saveAndFlush(newTodo(oldSession.getId(), user, "old", STATUS_PENDING, PRIORITY_HIGH, T1));
        var session = new com.azhukov.agent.core.model.Session(newSession.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        var deleted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holderPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterPid = new java.util.concurrent.atomic.AtomicInteger();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var delegate = org.mockito.Mockito.mock(TodoRepository.class, invocation -> {
            try {
                var result = invocation.getMethod().invoke(todoRepository, invocation.getArguments());
                if (invocation.getMethod().getName().equals("deleteByUserId")) {
                    deleted.countDown();
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                }
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        var tool = new com.azhukov.agent.tools.memory.TodoTool(delegate);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var replacement = pool.submit(() -> transactions.execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return tool.execute("{\"todos\":[{\"content\":\"new\"}]}", null, session);
            }));
            assertThat(deleted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var removal = pool.submit(() -> transactions.execute(status -> {
                waiterPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                ready.countDown();
                boolean result = sessionQueryService.deleteSession(oldSession.getId());
                sessionRepository.flush();
                return result;
            }));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))", Boolean.class, holderPid.get(), waiterPid.get()));
                if (blocked) break;
                Thread.sleep(25);
            }
            assertThat(blocked).as("session cascade waits for concurrent deletion of its todo").isTrue();
            release.countDown();
            assertThat(replacement.get(10, java.util.concurrent.TimeUnit.SECONDS).success()).isTrue();
            assertThat(removal.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(sessionRepository.findById(oldSession.getId())).isEmpty();
            assertThat(todoRepository.findByUserId(user)).extracting(TodoEntity::getTitle).containsExactly("new");
            assertThat(todoRepository.findByUserId(user).getFirst().getSessionId()).isEqualTo(newSession.getId());
        } finally {
            release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            todoRepository.deleteByUserId(user); sessionRepository.deleteById(oldSession.getId()); sessionRepository.deleteById(newSession.getId());
        }
    }

    private void assertAdvisoryBlocked(int waiterPid, int holderPid) throws InterruptedException {
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        boolean blocked = false;
        while (System.nanoTime() < deadline) {
            blocked = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from pg_locks where pid = ? and locktype = 'advisory' and not granted)
                    and ? = any(pg_blocking_pids(?))
                """, Boolean.class, waiterPid, holderPid, waiterPid));
            if (blocked) break;
            Thread.sleep(25);
        }
        assertThat(blocked).as("waiter is blocked by replacement transaction's advisory lock").isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void markingDoneWaitsForReplacementAndDoesNotResurrectDeletedTodo(boolean scoped) throws Exception {
        String user = "done-race-" + UUID.randomUUID();
        var saved = sessionRepository.saveAndFlush(newSession(user, "Done race"));
        var original = todoRepository.saveAndFlush(newTodo(saved.getId(), user, "original", STATUS_PENDING, PRIORITY_HIGH, T1));
        var session = new com.azhukov.agent.core.model.Session(saved.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        var deleted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holderPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterReady = new java.util.concurrent.CountDownLatch(1);
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var delegate = org.mockito.Mockito.mock(TodoRepository.class, invocation -> {
            try {
                var result = invocation.getMethod().invoke(todoRepository, invocation.getArguments());
                if (invocation.getMethod().getName().equals("deleteByUserId")) {
                    deleted.countDown();
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                }
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        var tool = new com.azhukov.agent.tools.memory.TodoTool(delegate);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var replacement = pool.submit(() -> transactions.execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return tool.execute("{\"todos\":[{\"content\":\"replacement\"}]}", null, session);
            }));
            assertThat(deleted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var done = pool.submit(() -> transactions.execute(status -> {
                waiterPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                waiterReady.countDown();
                return scoped ? todoService.markDoneForUser(original.getId(), user) : todoService.markDone(original.getId());
            }));
            assertThat(waiterReady.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertAdvisoryBlocked(waiterPid.get(), holderPid.get());
            release.countDown();
            assertThat(replacement.get(10, java.util.concurrent.TimeUnit.SECONDS).success()).isTrue();
            assertThat(done.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
            assertThat(todoRepository.findByUserId(user)).extracting(TodoEntity::getTitle).containsExactly("replacement");
        } finally {
            release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            todoRepository.deleteByUserId(user); sessionRepository.deleteById(saved.getId());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void serviceClearWaitsForConcurrentToolReplacement() throws Exception {
        String user = "clear-race-" + UUID.randomUUID();
        var saved = sessionRepository.saveAndFlush(newSession(user, "Clear race"));
        var session = new com.azhukov.agent.core.model.Session(saved.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        var deleted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holderPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterReady = new java.util.concurrent.CountDownLatch(1);
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var delegate = org.mockito.Mockito.mock(TodoRepository.class, invocation -> {
            try {
                var result = invocation.getMethod().invoke(todoRepository, invocation.getArguments());
                if (invocation.getMethod().getName().equals("deleteByUserId")) {
                    deleted.countDown();
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                }
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        var tool = new com.azhukov.agent.tools.memory.TodoTool(delegate);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var replacement = pool.submit(() -> transactions.execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return tool.execute("{\"todos\":[{\"content\":\"replacement\"}]}", null, session);
            }));
            assertThat(deleted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var clear = pool.submit(() -> transactions.execute(status -> {
                waiterPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                waiterReady.countDown();
                todoService.clearByUserId(user);
                return null;
            }));
            assertThat(waiterReady.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertAdvisoryBlocked(waiterPid.get(), holderPid.get());
            release.countDown();
            assertThat(replacement.get(10, java.util.concurrent.TimeUnit.SECONDS).success()).isTrue();
            clear.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(todoRepository.findByUserId(user)).isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            todoRepository.deleteByUserId(user); sessionRepository.deleteById(saved.getId());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void serviceAddWaitsForConcurrentToolReplacement() throws Exception {
        String user = "mixed-" + UUID.randomUUID();
        var saved = sessionRepository.saveAndFlush(newSession(user, "Mixed writes"));
        var session = new com.azhukov.agent.core.model.Session(saved.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        var deleted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holderPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterPid = new java.util.concurrent.atomic.AtomicInteger();
        var waiterReady = new java.util.concurrent.CountDownLatch(1);
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var delegate = org.mockito.Mockito.mock(TodoRepository.class, invocation -> {
            try {
                var result = invocation.getMethod().invoke(todoRepository, invocation.getArguments());
                if (invocation.getMethod().getName().equals("deleteByUserId")) {
                    deleted.countDown();
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                }
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        var tool = new com.azhukov.agent.tools.memory.TodoTool(delegate);
        var transactions = new org.springframework.transaction.support.TransactionTemplate(applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var replacement = pool.submit(() -> transactions.execute(status -> {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return tool.execute("{\"todos\":[{\"content\":\"replacement\"}]}", null, session);
            }));
            assertThat(deleted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var add = pool.submit(() -> transactions.execute(status -> {
                waiterPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                waiterReady.countDown();
                return todoService.add(user, "service addition");
            }));
            assertThat(waiterReady.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertAdvisoryBlocked(waiterPid.get(), holderPid.get());
            release.countDown();
            assertThat(replacement.get(10, java.util.concurrent.TimeUnit.SECONDS).success()).isTrue();
            assertThat(add.get(10, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
            assertThat(todoRepository.findByUserId(user)).extracting(TodoEntity::getTitle).containsExactlyInAnyOrder("replacement", "service addition");
        } finally {
            release.countDown(); pool.shutdownNow(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            todoRepository.deleteByUserId(user); sessionRepository.deleteById(saved.getId());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void userLockIsReleasedAfterTransactionRollback() throws Exception {
        String user = "rollback-lock-" + UUID.randomUUID();
        var template = new org.springframework.transaction.support.TransactionTemplate(
            applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var holding = new java.util.concurrent.CountDownLatch(1);
        var rollback = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var failed = pool.submit(() -> template.execute(status -> {
                todoRepository.lockUserList(user);
                holding.countDown();
                try {
                    if (!rollback.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("rollback timeout");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                throw new IllegalStateException("force rollback");
            }));
            assertThat(holding.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> template.execute(status -> {
                applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class).execute("set local lock_timeout = '250ms'");
                return todoRepository.lockUserList(user);
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
            rollback.countDown();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> failed.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
            Integer result = template.execute(status -> {
                applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class).execute("set local lock_timeout = '2s'");
                return todoRepository.lockUserList(user);
            });
            assertThat(result).isEqualTo(1);
        } finally {
            rollback.countDown(); pool.shutdownNow(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentReplacementAndWriteAreSerialized(boolean merge) throws Exception {
        String user = "concurrent-" + UUID.randomUUID();
        var saved = sessionRepository.saveAndFlush(newSession(user, "Concurrent replacement"));
        var session = new com.azhukov.agent.core.model.Session(saved.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        var deletionReached = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var bAttemptingLock = new java.util.concurrent.CountDownLatch(1);
        var delegate = org.mockito.Mockito.mock(TodoRepository.class, invocation -> {
            try {
                if (invocation.getMethod().getName().equals("lockUserList") && Thread.currentThread().getName().equals("todo-second")) bAttemptingLock.countDown();
                if (invocation.getMethod().getName().equals("deleteByUserId")) {
                    var result = invocation.getMethod().invoke(todoRepository, invocation.getArguments());
                    if (first.compareAndSet(true, false)) {
                        deletionReached.countDown();
                        if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                    }
                    return result;
                }
                return invocation.getMethod().invoke(todoRepository, invocation.getArguments());
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        var target = new com.azhukov.agent.tools.memory.TodoTool(delegate);
        var txManager = applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        var template = new org.springframework.transaction.support.TransactionTemplate(txManager);
        String otherUser = "independent-" + UUID.randomUUID();
        var otherSaved = sessionRepository.saveAndFlush(newSession(otherUser, "Independent"));
        var otherSession = new com.azhukov.agent.core.model.Session(otherSaved.getId(), otherUser, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        var firstPid = new java.util.concurrent.atomic.AtomicInteger();
        var secondPid = new java.util.concurrent.atomic.AtomicInteger();
        var jdbc = applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var a = pool.submit(() -> template.execute(status -> {
                firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return target.execute("{\"todos\":[{\"content\":\"plan-a\"}]}", null, session);
            }));
            assertThat(deletionReached.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var b = pool.submit(() -> { Thread.currentThread().setName("todo-second"); return template.execute(status -> {
                secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return target.execute("{\"merge\":" + merge + ",\"todos\":[{\"content\":\"plan-b\"}]}", null, session);
            }); });
            assertThat(bAttemptingLock.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists (select 1 from pg_locks
                      where pid = ? and locktype = 'advisory' and not granted)
                      and ? = any(pg_blocking_pids(?))
                    """, Boolean.class, secondPid.get(), firstPid.get(), secondPid.get()));
                if (waiting) break;
                Thread.sleep(25);
            }
            assertThat(waiting).as("second transaction is waiting on an advisory lock held by first").isTrue();
            var independent = pool.submit(() -> template.execute(status -> target.execute("{\"todos\":[{\"content\":\"other\"}]}", null, otherSession)));
            assertThat(independent.get(5, java.util.concurrent.TimeUnit.SECONDS).success()).isTrue();
            release.countDown();
            assertThat(a.get(10, java.util.concurrent.TimeUnit.SECONDS).success()).isTrue();
            assertThat(b.get(10, java.util.concurrent.TimeUnit.SECONDS).success()).isTrue();
            var titles = todoRepository.findByUserId(user).stream().map(TodoEntity::getTitle).toList();
            if (merge) assertThat(titles).containsExactlyInAnyOrder("plan-a", "plan-b");
            else assertThat(titles).containsExactly("plan-b");
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            todoRepository.deleteByUserId(user);
            todoRepository.deleteByUserId(otherUser);
            sessionRepository.deleteById(otherSaved.getId());
            sessionRepository.deleteById(saved.getId());
        }
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void registryExecutesTransactionalTodoWithAtomicRollback() {
        String user = "registry-" + UUID.randomUUID();
        var saved = sessionRepository.saveAndFlush(newSession(user, "Registry atomicity"));
        var session = new com.azhukov.agent.core.model.Session(saved.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        try {
            var first = registry.execute("todo", "first", "{\"todos\":[{\"content\":\"original\"}]}", null, session);
            assertThat(first.success()).isTrue();
            assertThat(todoRepository.findByUserId(user)).extracting(TodoEntity::getTitle).containsExactly("original");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.execute("todo", "second",
                "{\"todos\":[{\"content\":\"replacement\"},{\"content\":\"bad\\u0000value\"}]}", null, session))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(todoRepository.findByUserId(user)).extracting(TodoEntity::getTitle).containsExactly("original");
            var finalResult = registry.execute("todo", "third", "{\"todos\":[{\"content\":\"final\"}]}", null, session);
            assertThat(finalResult.success()).isTrue();
            assertThat(todoRepository.findByUserId(user)).extracting(TodoEntity::getTitle).containsExactly("final");
        } finally {
            todoRepository.deleteByUserId(user);
            sessionRepository.deleteById(saved.getId());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void replacementRollsBackIfLaterInsertFailsAtDatabase() {
        String user = "rollback-" + java.util.UUID.randomUUID();
        var sessionEntity = sessionRepository.saveAndFlush(newSession(user, "Atomic replacement"));
        var tool = todoTool;
        var session = new com.azhukov.agent.core.model.Session(sessionEntity.getId(), user, "owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        tool.execute("{\"todos\":[{\"content\":\"original\"}]}", null, session);
        assertThat(todoRepository.findByUserId(user)).extracting(TodoEntity::getTitle).containsExactly("original");
        try {
            tool.execute("{\"todos\":[{\"content\":\"first replacement\"},{\"content\":\"bad\\u0000value\"}]}", null, session);
            org.assertj.core.api.Assertions.fail("Expected PostgreSQL to reject NUL in text");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertThat(todoRepository.findByUserId(user)).extracting(TodoEntity::getTitle).containsExactly("original");
        } finally {
            todoRepository.deleteByUserId(user);
            sessionRepository.deleteById(sessionEntity.getId());
        }
    }

    @Test
    void malformedToolReplacementPreservesPersistedListsAndValidReplacementStillWorks() {
        SessionEntity first = sessionRepository.save(newSession(USER_ID_1, "Owner"));
        SessionEntity second = sessionRepository.save(newSession(USER_ID_2, "Other"));
        todoRepository.save(newTodo(first.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        todoRepository.save(newTodo(second.getId(), USER_ID_2, TITLE_READ_BOOK, STATUS_PENDING, PRIORITY_HIGH, T2));
        todoRepository.flush();
        var tool = new com.azhukov.agent.tools.memory.TodoTool(todoRepository);
        var owner = new com.azhukov.agent.core.model.Session(first.getId(), USER_ID_1, "Owner", MODEL_PROVIDER, MODEL_NAME, null, java.util.Map.of());
        for (int i = 0; i < 2; i++) {
            var result = tool.execute("{\"todos\":[{\"content\":\"valid\"},null]}", null, owner);
            assertThat(result.success()).isFalse();
            assertThat(todoRepository.findByUserId(USER_ID_1)).extracting(TodoEntity::getTitle).containsExactly(TITLE_BUY_MILK);
            assertThat(todoRepository.findByUserId(USER_ID_2)).extracting(TodoEntity::getTitle).containsExactly(TITLE_READ_BOOK);
        }
        var result = tool.execute("{\"todos\":[{\"content\":\"replacement\"}]}", null, owner);
        assertThat(result.success()).isTrue();
        todoRepository.flush();
        assertThat(todoRepository.findByUserId(USER_ID_1)).extracting(TodoEntity::getTitle).containsExactly("replacement");
        assertThat(todoRepository.findByUserId(USER_ID_2)).extracting(TodoEntity::getTitle).containsExactly(TITLE_READ_BOOK);
    }

    @Test
    void saveTodoForUser() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Todo Session"));
        TodoEntity todo = newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1);

        TodoEntity saved = todoRepository.save(todo);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSessionId()).isEqualTo(session.getId());
        assertThat(saved.getUserId()).isEqualTo(USER_ID_1);
        assertThat(saved.getTitle()).isEqualTo(TITLE_BUY_MILK);
        assertThat(saved.getStatus()).isEqualTo(STATUS_PENDING);
        assertThat(saved.getPriority()).isEqualTo(PRIORITY_HIGH);
        assertThat(saved.getCreatedAt()).isEqualTo(T1);
    }

    @Test
    void findByUserIdReturnsOnlyUsersTodos() {
        SessionEntity session1 = sessionRepository.save(newSession(USER_ID_1, "User 1 Session"));
        SessionEntity session2 = sessionRepository.save(newSession(USER_ID_2, "User 2 Session"));

        TodoEntity todo1 = todoRepository.save(newTodo(session1.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        TodoEntity todo2 = todoRepository.save(newTodo(session1.getId(), USER_ID_1, TITLE_WALK_DOG, STATUS_COMPLETED, PRIORITY_LOW, T2));
        todoRepository.save(newTodo(session2.getId(), USER_ID_2, TITLE_READ_BOOK, STATUS_PENDING, PRIORITY_HIGH, T3));

        List<TodoEntity> user1Todos = todoRepository.findByUserId(USER_ID_1);

        assertThat(user1Todos).hasSize(2);
        assertThat(user1Todos)
            .extracting(TodoEntity::getId)
            .containsExactlyInAnyOrder(todo1.getId(), todo2.getId());
        assertThat(user1Todos)
            .extracting(TodoEntity::getUserId)
            .containsOnly(USER_ID_1);
    }

    @Test
    void findByUserIdReturnsEmptyForUnknownUser() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Known User Session"));
        todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));

        List<TodoEntity> found = todoRepository.findByUserId(UNKNOWN_USER_ID);

        assertThat(found).isEmpty();
    }

    @Test
    void findBySessionIdOrderByCreatedAtAscReturnsOnlySessionTodosInOrder() {
        SessionEntity session1 = sessionRepository.save(newSession(USER_ID_1, "First Session"));
        SessionEntity session2 = sessionRepository.save(newSession(USER_ID_1, "Second Session"));
        todoRepository.save(newTodo(session1.getId(), USER_ID_1, TITLE_WALK_DOG, STATUS_PENDING, PRIORITY_LOW, T2));
        todoRepository.save(newTodo(session1.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        todoRepository.save(newTodo(session2.getId(), USER_ID_1, TITLE_READ_BOOK, STATUS_PENDING, PRIORITY_HIGH, T3));

        List<TodoEntity> found = todoRepository.findBySessionIdOrderByCreatedAtAsc(session1.getId());

        assertThat(found)
            .extracting(TodoEntity::getTitle)
            .containsExactly(TITLE_BUY_MILK, TITLE_WALK_DOG);
    }

    @Test
    void findByUserIdAndStatusFiltersByStatus() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Status Filter Session"));

        TodoEntity pendingTodo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_WALK_DOG, STATUS_COMPLETED, PRIORITY_LOW, T2));
        TodoEntity inProgressTodo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_READ_BOOK, STATUS_IN_PROGRESS, PRIORITY_HIGH, T3));
        todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_PAY_BILLS, STATUS_COMPLETED, PRIORITY_LOW, T4));

        List<TodoEntity> pending = todoRepository.findByUserIdAndStatus(USER_ID_1, STATUS_PENDING);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getId()).isEqualTo(pendingTodo.getId());
        assertThat(pending.get(0).getStatus()).isEqualTo(STATUS_PENDING);

        List<TodoEntity> inProgress = todoRepository.findByUserIdAndStatus(USER_ID_1, STATUS_IN_PROGRESS);
        assertThat(inProgress).hasSize(1);
        assertThat(inProgress.get(0).getId()).isEqualTo(inProgressTodo.getId());
        assertThat(inProgress.get(0).getStatus()).isEqualTo(STATUS_IN_PROGRESS);

        List<TodoEntity> completed = todoRepository.findByUserIdAndStatus(USER_ID_1, STATUS_COMPLETED);
        assertThat(completed).hasSize(2);
        assertThat(completed)
            .extracting(TodoEntity::getTitle)
            .containsExactlyInAnyOrder(TITLE_WALK_DOG, TITLE_PAY_BILLS);
    }

    @Test
    void updateStatus() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Update Status Session"));
        TodoEntity todo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        UUID todoId = todo.getId();

        todo.setStatus(STATUS_COMPLETED);
        todoRepository.save(todo);

        TodoEntity found = todoRepository.findById(todoId).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(STATUS_COMPLETED);
        assertThat(found.getTitle()).isEqualTo(TITLE_BUY_MILK);
    }

    @Test
    void deleteCompletedTodos() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Delete Completed Session"));
        SessionEntity session2 = sessionRepository.save(newSession(USER_ID_2, "User 2 Session"));

        TodoEntity pendingTodo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        TodoEntity completedTodo1 = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_WALK_DOG, STATUS_COMPLETED, PRIORITY_LOW, T2));
        TodoEntity inProgressTodo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_READ_BOOK, STATUS_IN_PROGRESS, PRIORITY_HIGH, T3));
        TodoEntity completedTodo2 = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_PAY_BILLS, STATUS_COMPLETED, PRIORITY_LOW, T4));
        todoRepository.save(newTodo(session2.getId(), USER_ID_2, TITLE_READ_BOOK, STATUS_COMPLETED, PRIORITY_HIGH, T1));

        todoRepository.deleteByUserIdAndStatus(USER_ID_1, STATUS_COMPLETED);

        List<TodoEntity> remaining = todoRepository.findByUserId(USER_ID_1);
        assertThat(remaining).hasSize(2);
        assertThat(remaining)
            .extracting(TodoEntity::getId)
            .containsExactlyInAnyOrder(pendingTodo.getId(), inProgressTodo.getId());
        assertThat(todoRepository.findById(completedTodo1.getId())).isEmpty();
        assertThat(todoRepository.findById(completedTodo2.getId())).isEmpty();

        List<TodoEntity> user2Todos = todoRepository.findByUserId(USER_ID_2);
        assertThat(user2Todos).hasSize(1);
    }

    @Test
    void deleteBySessionIdDeletesOnlyThatSession() {
        SessionEntity session1 = sessionRepository.save(newSession(USER_ID_1, "Delete Session 1"));
        SessionEntity session2 = sessionRepository.save(newSession(USER_ID_1, "Keep Session 2"));
        todoRepository.save(newTodo(session1.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        todoRepository.save(newTodo(session2.getId(), USER_ID_1, TITLE_READ_BOOK, STATUS_PENDING, PRIORITY_HIGH, T2));

        todoRepository.deleteBySessionId(session1.getId());

        assertThat(todoRepository.findBySessionId(session1.getId())).isEmpty();
        assertThat(todoRepository.findBySessionId(session2.getId()))
            .singleElement()
            .satisfies(todo -> assertThat(todo.getTitle()).isEqualTo(TITLE_READ_BOOK));
    }

    @Test
    void deleteBySessionIdAndUserIdKeepsSameSessionOtherUserTodos() {
        SessionEntity session = sessionRepository.save(newSession(USER_ID_1, "Shared Session"));
        TodoEntity userTodo = todoRepository.save(newTodo(session.getId(), USER_ID_1, TITLE_BUY_MILK, STATUS_PENDING, PRIORITY_HIGH, T1));
        TodoEntity otherUserTodo = todoRepository.save(newTodo(session.getId(), USER_ID_2, TITLE_READ_BOOK, STATUS_PENDING, PRIORITY_LOW, T2));

        todoRepository.deleteBySessionIdAndUserId(session.getId(), USER_ID_1);

        assertThat(todoRepository.findById(userTodo.getId())).isEmpty();
        assertThat(todoRepository.findById(otherUserTodo.getId())).isPresent();
    }

    private SessionEntity newSession(String userId, String title) {
        SessionEntity session = new SessionEntity();
        session.setUserId(userId);
        session.setTitle(title);
        session.setModelProvider(MODEL_PROVIDER);
        session.setModelName(MODEL_NAME);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return session;
    }

    private TodoEntity newTodo(UUID sessionId, String userId, String title, String status, String priority, Instant createdAt) {
        TodoEntity todo = new TodoEntity();
        todo.setSessionId(sessionId);
        todo.setUserId(userId);
        todo.setTitle(title);
        todo.setStatus(status);
        todo.setPriority(priority);
        todo.setCreatedAt(createdAt);
        return todo;
    }
}
