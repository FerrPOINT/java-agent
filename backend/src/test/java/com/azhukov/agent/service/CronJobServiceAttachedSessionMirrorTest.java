package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.CronExecutionLogEntity;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hermes {@code _maybe_mirror_cron_delivery} parity: a job with
 * {@code attach_to_session} appends its delivered output (or compact failure
 * line) into the attached session as a user-role note at the next free turn
 * boundary. Role is user, never assistant (Hermes #2221 — assistant-role
 * mirrors break strict-alternation providers). Mirror is best-effort: a
 * mirror failure never fails the ledger delivery.
 */
@ExtendWith(MockitoExtension.class)
class CronJobServiceAttachedSessionMirrorTest {

    @Mock private CronJobRepository cronJobRepository;
    @Mock private ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    @Mock private SkillManager skillManager;
    @Mock private CronExecutionLogRepository cronExecutionLogRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ObjectProvider<DeliveryWorkItemService> deliveryProvider;
    @Mock private ObjectProvider<com.azhukov.agent.persistence.repository.SessionRepository> sessionRepositoryProvider;
    @Mock private DeliveryWorkItemService deliveryService;
    @Mock private AgentRuntimeService runtime;

    private CronJobService service() {
        return new CronJobService(cronJobRepository, agentRuntimeServiceProvider, properties(),
            skillManager, cronExecutionLogRepository, messageRepository,
            transactionTemplate, new CronScheduleParser(),
            deliveryProvider, sessionRepositoryProvider, null);
    }

    private AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.getCron().setEnabled(false);
        return properties;
    }

    private CronJobEntity job(UUID attachedSessionId) {
        CronJobEntity job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setName("daily-report");
        job.setSchedule("0 9 * * *");
        job.setPrompt("report");
        job.setDeliverTo("telegram:111");
        job.setEnabled(true);
        job.setProfile("default");
        job.setCreatedAt(Instant.now());
        job.setAttachedSessionId(attachedSessionId);
        return job;
    }

    private void prepareFailingRun(CronJobEntity job, UUID attachedSessionId) {
        when(cronJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(agentRuntimeServiceProvider.getIfAvailable()).thenReturn(runtime);
        when(runtime.runBackground(any(), any(), anyBoolean()))
            .thenThrow(new IllegalStateException("provider timed out"));
        when(cronExecutionLogRepository.save(any(CronExecutionLogEntity.class))).thenAnswer(inv -> {
            CronExecutionLogEntity entity = inv.getArgument(0);
            entity.setId(42L);
            return entity;
        });
        when(deliveryProvider.getIfAvailable()).thenReturn(deliveryService);
        // Lenient: target-less local runs never reach the ledger enqueue.
        org.mockito.Mockito.lenient()
            .when(deliveryService.enqueue(any())).thenAnswer(inv -> inv.getArgument(0));
        // Run the tx callback inline (no real transaction manager in unit tests).
        // Lenient: the no-attached-session path never opens a mirror transaction.
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        if (attachedSessionId != null) {
            // Session already has turns 0,2,5 — the mirror note must land at 6.
            when(messageRepository.findTurnIndicesBySessionIdDesc(attachedSessionId))
                .thenReturn(List.of(5, 2, 0));
        }
    }

    @Test
    void failureOutputMirrorsIntoAttachedSessionAsUserRoleAtNextTurnBoundary() {
        UUID attachedSessionId = UUID.randomUUID();
        CronJobEntity job = job(attachedSessionId);
        prepareFailingRun(job, attachedSessionId);

        service().runNow(job.getId());

        // The ledger delivery still happens (mirror is additive, not a replacement).
        verify(deliveryService).enqueue(any());
        ArgumentCaptor<MessageEntity> saved = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository).save(saved.capture());
        MessageEntity note = saved.getValue();
        assertThat(note.getSessionId()).isEqualTo(attachedSessionId);
        // Hermes #2221: the cron brief is NOT the agent speaking — role=user so
        // strict-alternation providers never see assistant→assistant pairs.
        assertThat(note.getRole()).isEqualTo("user");
        assertThat(note.getTurnIndex()).isEqualTo(6);
        assertThat(note.getContent()).contains("[cron 'daily-report' output]");
        assertThat(note.getContent()).contains("Cron 'daily-report' failed: provider timed out");
    }

    @Test
    void jobWithoutAttachedSessionNeverTouchesMessageRepository() {
        CronJobEntity job = job(null);
        prepareFailingRun(job, null);

        service().runNow(job.getId());

        verify(deliveryService).enqueue(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void localDeliveryJobStillMirrorsIntoAttachedSession() {
        // deliver=local resolves no ledger target — the transcript mirror must
        // still land (Hermes mirror is independent of the transport target).
        UUID attachedSessionId = UUID.randomUUID();
        CronJobEntity job = job(attachedSessionId);
        job.setDeliverTo("local");
        prepareFailingRun(job, attachedSessionId);

        service().runNow(job.getId());

        // No ledger enqueue for a local target, but the mirror note lands.
        verify(deliveryService, never()).enqueue(any());
        ArgumentCaptor<MessageEntity> saved = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo("user");
        assertThat(saved.getValue().getSessionId()).isEqualTo(attachedSessionId);
    }

    @Test
    void mirrorFailureNeverFailsTheLedgerDelivery() {
        UUID attachedSessionId = UUID.randomUUID();
        CronJobEntity job = job(attachedSessionId);
        prepareFailingRun(job, attachedSessionId);
        // Mirror write blows up — the delivery enqueue must still happen.
        org.mockito.Mockito.doThrow(new IllegalStateException("db gone"))
            .when(messageRepository).save(any(MessageEntity.class));

        service().runNow(job.getId());

        verify(deliveryService).enqueue(any());
    }
}
