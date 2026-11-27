package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.service.OpenAiSessionService.OpenAiSessionContext;
import com.azhukov.agent.persistence.entity.OpenAiRunEventEntity;
import com.azhukov.agent.persistence.entity.OpenAiRunStateEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.service.OpenAiRunStateMachine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-6 (V59) PostgreSQL slowTest: guarded transitions, terminal idempotency,
 * monotonic event replay, external id uniqueness per user.
 */
@Tag("slow")
class OpenAiRunStateRepositoryTest extends PostgresTestContainer {

    @Autowired
    private OpenAiRunStateRepository stateRepository;

    @Autowired
    private OpenAiRunEventRepository eventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private SessionRepository sessions;

    private OpenAiSessionContext sessionContext() {
        return new OpenAiSessionContext(Session.create("user-a", "noop", "model"), false, null);
    }

    private OpenAiRunStateEntity row(String runId, String state) {
        OpenAiRunStateEntity entity = new OpenAiRunStateEntity();
        entity.setRunId(runId);
        entity.setSessionId(UUID.randomUUID());
        entity.setProfile("default");
        entity.setState(state);
        entity.setLastSeq(0);
        return entity;
    }

    @Autowired
    private OpenAiRunStateMachine stateMachine;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Test
    void postgresRejectsExactForeignRunAndEventOperationsUnderTheWorkerRole() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String worker = "java_run_worker_" + suffix;
        String alpha = "run_alpha_" + suffix;
        String beta = "run_beta_" + suffix;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE ROLE " + worker + " NOSUPERUSER NOBYPASSRLS NOINHERIT");
                sql.execute("GRANT USAGE ON SCHEMA public TO " + worker);
                sql.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + worker);
                sql.execute("REVOKE ALL ON TABLE flyway_schema_history FROM " + worker);
                sql.execute("GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO " + worker);
                sql.execute("INSERT INTO openai_run_states(run_id, session_id, profile, state) VALUES "
                    + "('" + alpha + "', gen_random_uuid(), 'alpha', 'completed'), "
                    + "('" + beta + "', gen_random_uuid(), 'beta', 'completed')");
                sql.execute("INSERT INTO openai_run_events(run_id, seq, event_json) VALUES "
                    + "('" + alpha + "', 1, '{}'), ('" + beta + "', 1, '{}')");
                sql.execute("SET LOCAL ROLE " + worker);

                try (var rows = sql.executeQuery("SELECT run_id FROM openai_run_states")) {
                    assertThat(rows.next()).isFalse();
                }
                for (var scope : List.of(List.of("alpha", alpha, beta), List.of("beta", beta, alpha))) {
                    var unbound = connection.setSavepoint();
                    try (var bind = connection.prepareStatement("SELECT set_config('pdlc.java.profile', ?, true)")) {
                        bind.setString(1, scope.get(0));
                        bind.execute();
                    }
                    String own = scope.get(1);
                    String foreign = scope.get(2);
                    for (String table : List.of("openai_run_states", "openai_run_events")) {
                        try (var rows = sql.executeQuery("SELECT run_id FROM " + table
                                + " WHERE run_id IN ('" + own + "','" + foreign + "')")) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getString(1)).isEqualTo(own);
                            assertThat(rows.next()).isFalse();
                        }
                    }
                    assertThat(sql.executeUpdate("INSERT INTO openai_run_events(run_id, seq, event_json)"
                        + " VALUES ('" + own + "', 2, '{}')")).isEqualTo(1);
                    assertThat(sql.executeUpdate("UPDATE openai_run_states SET model='foreign-edit' WHERE run_id='"
                        + foreign + "'")).isZero();
                    assertThat(sql.executeUpdate("DELETE FROM openai_run_events WHERE run_id='" + foreign + "'")).isZero();
                    assertThatThrownBy(() -> sql.executeUpdate(
                            "INSERT INTO openai_run_events(run_id, seq, event_json) VALUES ('" + foreign + "', 2, '{}')"))
                        .isInstanceOfSatisfying(SQLException.class, error -> {
                            assertThat(error.getSQLState()).isEqualTo("42501");
                            assertThat(error.getMessage()).contains("row-level security policy");
                        });
                    connection.rollback(unbound);
                }
                try (var rows = sql.executeQuery("SELECT run_id FROM openai_run_events")) {
                    assertThat(rows.next()).isFalse();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void concurrentAcceptanceCommitsOneRunOneSessionAndOneInitialEvent() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        var barrier = new CyclicBarrier(2);
        var created = new ConcurrentLinkedQueue<UUID>();
        Supplier<OpenAiSessionContext> resolve = () -> {
            var row = new SessionEntity();
            row.setUserId("fixture-acceptance");
            row.setModelProvider("noop");
            row.setModelName("test-model");
            row.setProfile("alpha");
            row.setCreatedAt(Instant.now());
            row.setUpdatedAt(Instant.now());
            var id = sessions.saveAndFlush(row).getId();
            created.add(id);
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new OpenAiSessionContext(new Session(id, row.getUserId(), null, "noop",
                "test-model", null, java.util.Map.of("profile", "alpha")), false, null, "external-chat");
        };
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> stateMachine.accept(runId, "test-model", fingerprint, resolve));
            var second = workers.submit(() -> stateMachine.accept(runId, "test-model", fingerprint, resolve));
            int winners = 0;
            for (var future : List.of(first, second)) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    winners++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause().getClass().getSimpleName()).isEqualTo("AlreadyAccepted");
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(created).hasSize(2);
            var receipt = stateMachine.state(runId, "alpha").orElseThrow();
            assertThat(receipt.getRequestFingerprint()).isEqualTo(fingerprint);
            assertThat(receipt.getAcceptedSessionId()).isEqualTo("external-chat");
            assertThat(receipt.getProfile()).isEqualTo("alpha");
            assertThat(sessions.findAllById(created)).extracting(SessionEntity::getId)
                .containsExactly(receipt.getSessionId());
            assertThat(stateMachine.replayParsed(runId, "alpha", 0, 10)).hasSize(1);
        } finally {
            transactionTemplate.executeWithoutResult(tx -> {
                eventRepository.deleteByRunId(runId);
                stateRepository.deleteById(runId);
                sessions.deleteAllById(created);
            });
        }
    }

    @Test
    void sseResumesFromCommittedSequenceAfterClientReconnect() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        stateMachine.accept(runId, "model", null, this::sessionContext);
        try {
            stateMachine.recordEvent(runId, "default", "in_progress", java.util.Map.of("event", "run.started"));
            stateMachine.recordEvent(runId, "default", null, java.util.Map.of("event", "message.delta", "delta", "remaining"));
            stateMachine.recordEvent(runId, "default", "completed", java.util.Map.of("event", "run.completed", "output", "done"));
            var stream = mvc.perform(get("/v1/runs/{id}/events", runId)
                    .header("Last-Event-ID", "2").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
            mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
            assertThat(stream.getResponse().getContentAsString())
                .containsPattern("(?s)id:3.*id:4")
                .contains("message.delta", "remaining", "run.completed")
                .doesNotContain("id:1", "id:2");
        } finally {
            transactionTemplate.executeWithoutResult(tx -> {
                eventRepository.deleteByRunId(runId);
                stateRepository.deleteById(runId);
            });
        }
    }

    @Test
    void durableLifecycleReplaysCommittedOutcomeAndRejectsLateEvents() {
        String id = "run-" + UUID.randomUUID();
        stateMachine.accept(id, "model", null, this::sessionContext);
        try {
            stateMachine.recordEvent(id, "default", "in_progress", java.util.Map.of("event", "run.started"));
            stateMachine.recordEvent(id, "default", null, java.util.Map.of("event", "run.stopping"));
            assertThat(stateMachine.state(id, "default").orElseThrow().getState()).isEqualTo("in_progress");
            stateMachine.recordEvent(id, "default", "cancelled", java.util.Map.of("event", "run.cancelled"));

            assertThatThrownBy(() -> stateMachine.recordEvent(id, "default", "completed",
                java.util.Map.of("event", "run.completed"))).isInstanceOf(IllegalStateException.class);
            assertThat(stateMachine.state(id, "default").orElseThrow().getState()).isEqualTo("cancelled");
            assertThat(stateMachine.replayParsed(id, "default", 1, 10)).extracting(event -> event.payload().get("event"))
                .containsExactly("run.started", "run.stopping", "run.cancelled");
        } finally {
            stateRepository.deleteById(id);
        }
    }

    @Test
    void concurrentTerminalEventsHaveOneWinner() throws Exception {
        String id = "run-" + UUID.randomUUID();
        stateMachine.accept(id, "model", null, this::sessionContext);
        stateMachine.recordEvent(id, "default", "in_progress", java.util.Map.of("event", "run.started"));
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var complete = workers.submit(() -> recordTerminal(id, "completed"));
            var cancel = workers.submit(() -> recordTerminal(id, "cancelled"));
            assertThat(List.of(complete.get(), cancel.get())).containsExactlyInAnyOrder(true, false);
            assertThat(stateMachine.state(id, "default").orElseThrow().getLastSeq()).isEqualTo(3L);
            assertThat(stateMachine.replayParsed(id, "default", 2, 10)).hasSize(1);
        } finally {
            stateRepository.deleteById(id);
        }
    }

    private boolean recordTerminal(String id, String status) {
        try {
            stateMachine.recordEvent(id, "default", status, java.util.Map.of("event", "run." + status));
            return true;
        } catch (IllegalStateException e) {
            assertThat(e).hasMessageContaining("already terminal");
            return false;
        }
    }

    @Test
    void eventsReplayMonotonicallyAfterCursor() {
        String runId = "wp6-r3";
        transactionTemplate.executeWithoutResult(tx -> {
            stateRepository.save(row(runId, "completed"));
            for (long seq = 1; seq <= 5; seq++) {
                OpenAiRunEventEntity event = new OpenAiRunEventEntity();
                event.setRunId(runId);
                event.setSeq(seq);
                event.setEventJson("{\"event\":\"step." + seq + "\"}");
                eventRepository.save(event);
            }
        });

        var fromZero = eventRepository.replayAfter(runId, "default", 0,
            org.springframework.data.domain.PageRequest.of(0, 100));
        var fromCursor = eventRepository.replayAfter(runId, "default", 2,
            org.springframework.data.domain.PageRequest.of(0, 100));

        assertThat(fromZero).hasSize(5);
        assertThat(fromCursor).hasSize(3);
        assertThat(fromCursor.stream().map(OpenAiRunEventEntity::getSeq).toList())
            .containsExactly(3L, 4L, 5L);
        assertThat(eventRepository.countByRunId(runId)).isEqualTo(5);

        transactionTemplate.executeWithoutResult(tx -> {
            eventRepository.deleteByRunId(runId);
            stateRepository.deleteById(runId);
        });
    }

    @Test
    void externalRunIdUniquePerUser() {
        var run = transactionTemplate.execute(tx ->
            stateRepository.save(row("wp6-r4", "queued")));
        run.setUserId("user-a");
        run.setExternalRunId("ext-1");
        transactionTemplate.executeWithoutResult(tx -> stateRepository.save(run));

        var duplicate = row("wp6-r5", "queued");
        duplicate.setUserId("user-a");
        duplicate.setExternalRunId("ext-1");
        assertThatThrownBy(() -> transactionTemplate.execute(tx ->
                stateRepository.saveAndFlush(duplicate)))
            .isInstanceOf(Exception.class);

        // same external id for a DIFFERENT user is allowed
        var otherUser = row("wp6-r6", "queued");
        otherUser.setUserId("user-b");
        otherUser.setExternalRunId("ext-1");
        transactionTemplate.executeWithoutResult(tx -> stateRepository.save(otherUser));

        transactionTemplate.executeWithoutResult(tx -> stateRepository.deleteAll());
    }
}
