package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.OpenAiRunEventEntity;
import com.azhukov.agent.persistence.entity.OpenAiRunStateEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
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
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=none",
    "agent.model.provider=noop",
    "agent.memory.enabled=false",
    "agent.skills.enabled=false",
})
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

    @Test
    void guardedTransitionMovesStateExactlyOnce() {
        transactionTemplate.executeWithoutResult(tx -> {
            stateRepository.deleteAll();
            stateRepository.save(row("wp6-r1", "in_progress"));
        });

        int first = transactionTemplate.execute(tx -> stateRepository.transition(
            "wp6-r1", "in_progress", "requires_action", null, null, null, null, Instant.now()));
        int second = transactionTemplate.execute(tx -> stateRepository.transition(
            "wp6-r1", "in_progress", "requires_action", null, null, null, null, Instant.now()));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0); // state already moved — race loser
        assertThat(stateRepository.findById("wp6-r1").orElseThrow().getState())
            .isEqualTo("requires_action");
        transactionTemplate.executeWithoutResult(tx -> stateRepository.deleteAll());
    }

    @Test
    void cancelStampsCancelledAtOnce() {
        transactionTemplate.executeWithoutResult(tx ->
            stateRepository.save(row("wp6-r2", "requires_action")));

        transactionTemplate.executeWithoutResult(tx -> stateRepository.transition(
            "wp6-r2", "requires_action", "cancelled", "user request",
            "user request", Instant.now(), null, Instant.now()));

        var state = stateRepository.findById("wp6-r2").orElseThrow();
        assertThat(state.getState()).isEqualTo("cancelled");
        assertThat(state.getCancelledAt()).isNotNull();
        assertThat(state.getCancelReason()).isEqualTo("user request");

        // second cancel loses the race (state no longer requires_action)
        int second = transactionTemplate.execute(tx -> stateRepository.transition(
            "wp6-r2", "requires_action", "cancelled", null, null, null, null, Instant.now()));
        assertThat(second).isZero();
        transactionTemplate.executeWithoutResult(tx -> stateRepository.deleteAll());
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
