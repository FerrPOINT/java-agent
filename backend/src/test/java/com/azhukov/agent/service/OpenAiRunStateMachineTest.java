package com.azhukov.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-6: contract-first run state machine — transition table, idempotent
 * terminal states, race-safe guarded updates.
 */
@ExtendWith(MockitoExtension.class)
class OpenAiRunStateMachineTest {

    @Mock
    private com.azhukov.agent.persistence.repository.OpenAiRunStateRepository stateRepository;

    @Mock
    private com.azhukov.agent.persistence.repository.OpenAiRunEventRepository eventRepository;

    private static <T> ObjectProvider<T> prov(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    private OpenAiRunStateMachine machine() {
        return new OpenAiRunStateMachine(prov(stateRepository), prov(eventRepository),
            new ObjectMapper());
    }

    private com.azhukov.agent.persistence.entity.OpenAiRunStateEntity row(String runId, String state) {
        var entity = new com.azhukov.agent.persistence.entity.OpenAiRunStateEntity();
        entity.setRunId(runId);
        entity.setSessionId(UUID.randomUUID());
        entity.setState(state);
        entity.setLastSeq(0);
        return entity;
    }

    // ── transition table (pure contract) ────────────────────────────────

    @Test
    void transitionTableMatchesOpenAiRunsSemantics() {
        assertThat(OpenAiRunStateMachine.transitionAllowed("queued", "in_progress")).isTrue();
        assertThat(OpenAiRunStateMachine.transitionAllowed("queued", "cancelled")).isTrue();
        assertThat(OpenAiRunStateMachine.transitionAllowed("queued", "expired")).isTrue();
        assertThat(OpenAiRunStateMachine.transitionAllowed("in_progress", "requires_action")).isTrue();
        assertThat(OpenAiRunStateMachine.transitionAllowed("requires_action", "in_progress")).isTrue();
        assertThat(OpenAiRunStateMachine.transitionAllowed("in_progress", "completed")).isTrue();
        assertThat(OpenAiRunStateMachine.transitionAllowed("in_progress", "failed")).isTrue();
        // terminal states never leave
        assertThat(OpenAiRunStateMachine.transitionAllowed("completed", "in_progress")).isFalse();
        assertThat(OpenAiRunStateMachine.transitionAllowed("cancelled", "in_progress")).isFalse();
        assertThat(OpenAiRunStateMachine.transitionAllowed("failed", "completed")).isFalse();
        assertThat(OpenAiRunStateMachine.transitionAllowed("expired", "queued")).isFalse();
        // nonsense transitions
        assertThat(OpenAiRunStateMachine.transitionAllowed("queued", "completed")).isFalse();
        assertThat(OpenAiRunStateMachine.transitionAllowed("requires_action", "completed")).isFalse();
    }

    @Test
    void terminalStatesAreIdempotentTargets() {
        assertThat(OpenAiRunStateMachine.isTerminal("completed")).isTrue();
        assertThat(OpenAiRunStateMachine.isTerminal("failed")).isTrue();
        assertThat(OpenAiRunStateMachine.isTerminal("cancelled")).isTrue();
        assertThat(OpenAiRunStateMachine.isTerminal("expired")).isTrue();
        assertThat(OpenAiRunStateMachine.isTerminal("queued")).isFalse();
        assertThat(OpenAiRunStateMachine.isTerminal("in_progress")).isFalse();
        assertThat(OpenAiRunStateMachine.isTerminal("requires_action")).isFalse();
    }

    // ── guarded transition ──────────────────────────────────────────────

    @Test
    void sameStateTransitionIsIdempotent() {
        when(stateRepository.findById("r1")).thenReturn(Optional.of(row("r1", "in_progress")));
        var result = machine().transition("r1", "in_progress", null);
        assertThat(result.accepted()).isTrue();
        verify(stateRepository, never()).transition(anyString(), anyString(), anyString(),
            any(), any(), any(), any(), any());
    }

    @Test
    void invalidTransitionRejectedWithoutWrite() {
        when(stateRepository.findById("r1")).thenReturn(Optional.of(row("r1", "queued")));
        var result = machine().transition("r1", "completed", null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).contains("invalid transition");
        verify(stateRepository, never()).transition(anyString(), anyString(), anyString(),
            any(), any(), any(), any(), any());
    }

    @Test
    void terminalRunRejectsFurtherTransitions() {
        when(stateRepository.findById("r1")).thenReturn(Optional.of(row("r1", "completed")));
        var result = machine().transition("r1", "cancelled", "user request");
        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).contains("terminal");
    }

    @Test
    void lostRaceReportsActualState() {
        // first read: in_progress; guarded update reports 0 rows (lost race);
        // second read: another writer already moved it to cancelled
        when(stateRepository.findById("r1"))
            .thenReturn(Optional.of(row("r1", "in_progress")),
                Optional.of(row("r1", "cancelled")));
        when(stateRepository.transition(eq("r1"), eq("in_progress"), eq("cancelled"),
            any(), any(), any(), any(), any())).thenReturn(0);

        var result = machine().transition("r1", "cancelled", "user request");
        assertThat(result.accepted()).isFalse();
        assertThat(result.from()).isEqualTo("cancelled");
    }

    @Test
    void cancelFromRequiresActionAllowedAndStampsCancelledAt() {
        when(stateRepository.findById("r1")).thenReturn(Optional.of(row("r1", "requires_action")));
        when(stateRepository.transition(eq("r1"), eq("requires_action"), eq("cancelled"),
            eq("user request"), eq("user request"), any(), any(), any())).thenReturn(1);

        var result = machine().transition("r1", "cancelled", "user request");
        assertThat(result.accepted()).isTrue();
        assertThat(result.from()).isEqualTo("requires_action");
        assertThat(result.to()).isEqualTo("cancelled");
    }

    @Test
    void unknownRunRejected() {
        when(stateRepository.findById("missing")).thenReturn(Optional.empty());
        var result = machine().transition("missing", "cancelled", null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo("run not found");
    }

    @Test
    void appendEventAssignsMonotonicSeq() {
        var entity = row("r1", "in_progress");
        entity.setLastSeq(4);
        when(stateRepository.findById("r1")).thenReturn(Optional.of(entity));

        long seq = machine().appendEvent("r1", Map.of("event", "tool.approval.requested"));

        assertThat(seq).isEqualTo(5);
        assertThat(entity.getLastSeq()).isEqualTo(5);
        verify(eventRepository).save(any(com.azhukov.agent.persistence.entity.OpenAiRunEventEntity.class));
    }

    @Test
    void appendEventUnknownRunReturnsMinusOne() {
        when(stateRepository.findById("missing")).thenReturn(Optional.empty());
        assertThat(machine().appendEvent("missing", Map.of("event", "x"))).isEqualTo(-1);
    }
}
