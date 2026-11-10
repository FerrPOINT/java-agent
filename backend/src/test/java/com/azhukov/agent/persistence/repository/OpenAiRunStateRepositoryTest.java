package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.OpenAiRunEventEntity;
import com.azhukov.agent.persistence.entity.OpenAiRunStateEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private com.azhukov.agent.service.OpenAiRunStateMachine stateMachine;

    @Test
    void durableLifecycleReplaysCommittedOutcomeAndRejectsLateEvents() {
        String id = "run-" + UUID.randomUUID();
        stateMachine.createRun(id, UUID.randomUUID(), null, "default", "model", null);
        try {
            stateMachine.recordEvent(id, "in_progress", java.util.Map.of("event", "run.started"));
            stateMachine.recordEvent(id, null, java.util.Map.of("event", "run.stopping"));
            assertThat(stateMachine.state(id).orElseThrow().getState()).isEqualTo("in_progress");
            stateMachine.recordEvent(id, "cancelled", java.util.Map.of("event", "run.cancelled"));

            assertThatThrownBy(() -> stateMachine.recordEvent(id, "completed",
                java.util.Map.of("event", "run.completed"))).isInstanceOf(IllegalStateException.class);
            assertThat(stateMachine.state(id).orElseThrow().getState()).isEqualTo("cancelled");
            assertThat(stateMachine.replayParsed(id, 1, 10)).extracting(event -> event.payload().get("event"))
                .containsExactly("run.started", "run.stopping", "run.cancelled");
        } finally {
            stateRepository.deleteById(id);
        }
    }

    @Test
    void concurrentTerminalEventsHaveOneWinner() throws Exception {
        String id = "run-" + UUID.randomUUID();
        stateMachine.createRun(id, UUID.randomUUID(), null, "default", "model", null);
        stateMachine.recordEvent(id, "in_progress", java.util.Map.of("event", "run.started"));
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var complete = workers.submit(() -> recordTerminal(id, "completed"));
            var cancel = workers.submit(() -> recordTerminal(id, "cancelled"));
            assertThat(List.of(complete.get(), cancel.get())).containsExactlyInAnyOrder(true, false);
            assertThat(stateMachine.state(id).orElseThrow().getLastSeq()).isEqualTo(3L);
            assertThat(stateMachine.replayParsed(id, 2, 10)).hasSize(1);
        } finally {
            stateRepository.deleteById(id);
        }
    }

    private boolean recordTerminal(String id, String status) {
        try {
            stateMachine.recordEvent(id, status, java.util.Map.of("event", "run." + status));
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

        var fromZero = eventRepository.replayAfter(runId, 0,
            org.springframework.data.domain.PageRequest.of(0, 100));
        var fromCursor = eventRepository.replayAfter(runId, 2,
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
