package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.CronExecutionLogEntity;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-1 cutover: failed cron runs publish their compact operator alert through
 * the durable delivery ledger, with a target resolved once at enqueue time.
 */
@ExtendWith(MockitoExtension.class)
class CronJobServiceDeliveryProducerTest {

    @Mock private CronJobRepository cronJobRepository;
    @Mock private ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    @Mock private SkillManager skillManager;
    @Mock private CronExecutionLogRepository cronExecutionLogRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ObjectProvider<DeliveryWorkItemService> deliveryProvider;
    @Mock private ObjectProvider<com.azhukov.agent.persistence.repository.SessionRepository> sessionRepositoryProvider;
    @Mock private DeliveryWorkItemService deliveryService;
    @Mock private AgentRuntimeService runtime;

    private CronJobService service(AgentProperties properties) {
        return new CronJobService(cronJobRepository, agentRuntimeServiceProvider, properties,
            skillManager, cronExecutionLogRepository, messageRepository,
            new TransactionTemplate(), new CronScheduleParser(),
            deliveryProvider, sessionRepositoryProvider);
    }

    private CronJobEntity jobWithDeliverTo(String deliverTo) {
        CronJobEntity job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setName("daily-report");
        job.setSchedule("0 9 * * *");
        job.setPrompt("report");
        job.setDeliverTo(deliverTo);
        job.setEnabled(true);
        job.setProfile("default");
        job.setCreatedAt(Instant.now());
        return job;
    }

    private void prepareFailingRun(CronJobEntity job) {
        when(cronJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(agentRuntimeServiceProvider.getIfAvailable()).thenReturn(runtime);
        // Default-profile jobs run the 3-arg overload (metadata empty); non-default
        // profiles stamp "profile" into metadata and run the 4-arg one.
        when(runtime.runBackground(any(), any(), anyBoolean()))
            .thenThrow(new IllegalStateException("provider timed out"));
        when(cronExecutionLogRepository.save(any(CronExecutionLogEntity.class))).thenAnswer(inv -> {
            CronExecutionLogEntity entity = inv.getArgument(0);
            entity.setId(42L);
            return entity;
        });
        when(deliveryProvider.getIfAvailable()).thenReturn(deliveryService);
        when(deliveryService.enqueue(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void bareTelegramResolvesToOwnerChatFromGatewayConfig() {
        CronJobEntity job = jobWithDeliverTo("telegram");
        AgentProperties properties = new AgentProperties();
        properties.getCron().setEnabled(false);
        properties.getGateway().getTelegram().getAllowedUserIds().add("@admin");
        properties.getGateway().getTelegram().getAllowedUserIds().add("100200300");
        prepareFailingRun(job);

        service(properties).runNow(job.getId());

        var captor = ArgumentCaptor.forClass(DeliveryWorkItemService.EnqueueRequest.class);
        verify(deliveryService).enqueue(captor.capture());
        assertThat(captor.getValue().target()).isEqualTo("telegram:100200300");
    }

    @Test
    void failureDeliversCompactSummaryWithStreakNudge() {
        CronJobEntity job = jobWithDeliverTo("telegram:111");
        // executeJob increments the stored failure count before rendering.
        job.setConsecutiveFailures(2);
        AgentProperties properties = new AgentProperties();
        properties.getCron().setEnabled(false);
        properties.getCron().setNudgeFailureThreshold(3);
        prepareFailingRun(job);

        service(properties).runNow(job.getId());

        var captor = ArgumentCaptor.forClass(DeliveryWorkItemService.EnqueueRequest.class);
        verify(deliveryService).enqueue(captor.capture());
        String payload = captor.getValue().payloadText();
        assertThat(payload).contains("Cron 'daily-report' failed: provider timed out");
        assertThat(payload).contains("3 runs in a row");
        assertThat(captor.getValue().sourceType()).isEqualTo(DeliveryWorkItemService.SOURCE_CRON_EXECUTION);
        assertThat(captor.getValue().sourceId()).isEqualTo("42");
    }
}
