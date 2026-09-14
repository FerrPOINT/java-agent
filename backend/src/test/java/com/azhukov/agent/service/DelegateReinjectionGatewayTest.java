package com.azhukov.agent.service;

import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.persistence.entity.DelegatedTaskRunEntity;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DelegateReinjectionGatewayTest {

    @Mock private ObjectProvider<DelegatedTaskRunService> runsProvider;
    @Mock private ObjectProvider<SteerBuffer> steerProvider;
    @Mock private ObjectProvider<MessageRepository> messagesProvider;
    @Mock private ObjectProvider<TransactionTemplate> txProvider;
    @Mock private DelegatedTaskRunService runs;
    @Mock private SteerBuffer steerBuffer;
    @Mock private MessageRepository messages;
    @Mock private TransactionTemplate tx;

    private DelegateReinjectionGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new DelegateReinjectionGateway(
            runsProvider, steerProvider, messagesProvider, txProvider);
        when(runsProvider.getIfAvailable()).thenReturn(runs);
        when(steerProvider.getIfAvailable()).thenReturn(steerBuffer);
        when(messagesProvider.getIfAvailable()).thenReturn(messages);
        when(txProvider.getIfAvailable()).thenReturn(tx);
    }

    private static DelegatedTaskRunService.DeliveryClaim claim(DelegatedTaskRunEntity run, String claimId) {
        return new DelegatedTaskRunService.DeliveryClaim(run.getId(), claimId, run);
    }

    private static DelegatedTaskRunEntity terminalRun(UUID runId, UUID parentSessionId) {
        DelegatedTaskRunEntity run = new DelegatedTaskRunEntity();
        run.setId(runId);
        run.setParentSessionId(parentSessionId);
        run.setGoal("analyze parity gaps");
        run.setStatus("completed");
        run.setCompletedAt(Instant.now());
        run.setResultJson("{\"results\":[{\"summary\":\"found 3 gaps\"}]}");
        return run;
    }

    @Test
    void reinjectsCompletionIntoParentSessionAndAcksClaim() {
        UUID runId = UUID.randomUUID();
        UUID parentSessionId = UUID.randomUUID();
        DelegatedTaskRunEntity run = terminalRun(runId, parentSessionId);
        when(runs.claimNextPendingDelivery(anyString()))
            .thenReturn(Optional.of(claim(run, "gateway:abc")))
            .thenReturn(Optional.empty());
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                .accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        ArgumentCaptor<MessageEntity> saved = ArgumentCaptor.forClass(MessageEntity.class);

        gateway.reinjectPendingCompletions();

        verify(messages).save(saved.capture());
        MessageEntity note = saved.getValue();
        assertThat(note.getSessionId()).isEqualTo(parentSessionId);
        assertThat(note.getRole()).isEqualTo("assistant");
        assertThat(note.getContent()).contains("[delegated task completed]");
        assertThat(note.getContent()).contains(runId.toString());
        assertThat(note.getContent()).contains("status: completed");
        assertThat(note.getContent()).contains("found 3 gaps");
        assertThat(note.getContent()).contains("delegate_task action=read");
        verify(steerBuffer).steer(eq(parentSessionId), anyString());
        verify(runs).completeDeliveryClaim(runId, "gateway:abc", "parent_session", null);
        verify(runs, never()).releaseDeliveryClaim(any(), anyString(), anyString(), anyString());
    }

    @Test
    void releasesClaimWhenParentSessionIsMissing() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity run = terminalRun(runId, null);
        when(runs.claimNextPendingDelivery(anyString()))
            .thenReturn(Optional.of(claim(run, "gateway:abc")))
            .thenReturn(Optional.empty());

        gateway.reinjectPendingCompletions();

        verify(runs).releaseDeliveryClaim(
            eq(runId), eq("gateway:abc"), eq("parent_session"), anyString());
        verify(runs, never()).completeDeliveryClaim(any(), anyString(), anyString(), any());
        verify(messages, never()).save(any(MessageEntity.class));
    }

    @Test
    void releasesClaimAndContinuesWhenInjectionThrows() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity run = terminalRun(runId, UUID.randomUUID());
        when(runs.claimNextPendingDelivery(anyString()))
            .thenReturn(Optional.of(claim(run, "gateway:abc")))
            .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
            .when(tx).executeWithoutResult(any());

        gateway.reinjectPendingCompletions();

        verify(runs).releaseDeliveryClaim(
            eq(runId), eq("gateway:abc"), eq("parent_session"), eq("db down"));
    }

    @Test
    void boundsEachPassToTwentyCompletions() {
        UUID parentSessionId = UUID.randomUUID();
        DelegatedTaskRunEntity run = terminalRun(UUID.randomUUID(), parentSessionId);
        when(runs.claimNextPendingDelivery(anyString()))
            .thenReturn(Optional.of(claim(run, "gateway:abc")));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                .accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());

        gateway.reinjectPendingCompletions();

        verify(runs, org.mockito.Mockito.atMost(20)).completeDeliveryClaim(any(), anyString(), anyString(), any());
        verify(runs, org.mockito.Mockito.times(20)).claimNextPendingDelivery(anyString());
    }

    @Test
    void stopsImmediatelyWhenNothingIsPending() {
        when(runs.claimNextPendingDelivery(anyString())).thenReturn(Optional.empty());

        gateway.reinjectPendingCompletions();

        verify(runs, org.mockito.Mockito.times(1)).claimNextPendingDelivery(anyString());
        verify(messages, never()).save(any(MessageEntity.class));
    }
}
