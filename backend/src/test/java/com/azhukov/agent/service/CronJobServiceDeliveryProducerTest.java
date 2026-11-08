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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        return service(properties, new TransactionTemplate(mock(org.springframework.transaction.PlatformTransactionManager.class)));
    }

    private CronJobService service(AgentProperties properties, TransactionTemplate transactions) {
        return new CronJobService(cronJobRepository, agentRuntimeServiceProvider, properties,
            skillManager, cronExecutionLogRepository, messageRepository,
            transactions, new CronScheduleParser(),
            deliveryProvider, sessionRepositoryProvider, null);
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
        prepareRun(job);
        when(runtime.runBackground(any(), any(), anyBoolean()))
            .thenThrow(new IllegalStateException("provider timed out"));
    }

    private void prepareRun(CronJobEntity job) {
        lenient().when(cronJobRepository.recordRun(job)).thenReturn(1);
        when(cronJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(agentRuntimeServiceProvider.getIfAvailable()).thenReturn(runtime);
        when(cronExecutionLogRepository.save(any(CronExecutionLogEntity.class))).thenAnswer(inv -> {
            CronExecutionLogEntity entity = inv.getArgument(0);
            entity.setId(42L);
            return entity;
        });
        lenient().when(deliveryProvider.getIfAvailable()).thenReturn(deliveryService);
    }

    @Test
    void rejectedLedgerWriteRollsBackRunProjectionAndIsReportedToCaller() {
        var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID(), true);
        try {
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.execute("create table recorded_run (id integer)");
            var transactions = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
            var job = jobWithDeliverTo("telegram:111");
            var properties = new AgentProperties();
            properties.getCron().setEnabled(false);
            prepareFailingRun(job);
            when(cronJobRepository.recordRun(job))
                .thenAnswer(invocation -> jdbc.update("insert into recorded_run values (1)"));
            when(deliveryService.enqueue(any())).thenThrow(new IllegalArgumentException("ledger payload rejected"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(properties, transactions).runNow(job.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cron execution result could not be committed");
            assertThat(jdbc.queryForObject("select count(*) from recorded_run", Integer.class)).isZero();
            verify(cronExecutionLogRepository).save(any(CronExecutionLogEntity.class));
        } finally {
            dataSource.destroy();
        }
    }

    @Test
    void missingHistoryStopsBeforeReportingACommittedRun() {
        var job = jobWithDeliverTo("telegram:111");
        var properties = new AgentProperties();
        properties.getCron().setEnabled(false);
        when(cronJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(cronExecutionLogRepository.save(any(CronExecutionLogEntity.class)))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("history unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(properties).runNow(job.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Cron execution result could not be committed");
        verify(cronJobRepository, org.mockito.Mockito.never()).recordRun(any());
        verify(deliveryService, org.mockito.Mockito.never()).enqueue(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void oneShotConsumesItsTimerBeforeExecutingEvenWhenCompletionFails(boolean enqueueFails) {
        var job = jobWithDeliverTo("telegram:111");
        job.setRepeatCount(1);
        var properties = new AgentProperties();
        properties.getCron().setEnabled(true);
        prepareRun(job);
        var session = UUID.randomUUID();
        when(runtime.runBackground(any(), any(), anyBoolean())).thenAnswer(invocation -> {
            assertThat(job.isEnabled()).isFalse();
            assertThat(job.getNextRunAt()).isNull();
            return session.toString();
        });
        var message = new com.azhukov.agent.persistence.entity.MessageEntity();
        message.setRole("assistant");
        message.setContent("completed side effect");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(session)).thenReturn(List.of(message));
        if (enqueueFails) {
            when(deliveryService.enqueue(any())).thenThrow(new IllegalArgumentException("ledger rejected output"));
        } else {
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("completion write failed"))
                .when(cronJobRepository).delete(job);
        }
        when(cronJobRepository.findByEnabledTrue()).thenReturn(List.of(job));
        when(cronJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));

        var service = service(properties);
        var scheduler = mock(java.util.concurrent.ScheduledExecutorService.class);
        var timer = mock(java.util.concurrent.ScheduledFuture.class);
        var callback = ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.doReturn(timer).when(scheduler)
            .schedule(callback.capture(), org.mockito.ArgumentMatchers.anyLong(), any());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scheduler", scheduler);

        service.init();
        callback.getValue().run();

        assertThat(job.isEnabled()).isFalse();
        assertThat(job.getNextRunAt()).isNull();
        assertThat(job.getRepeatCompleted()).isZero();
        verify(cronJobRepository, org.mockito.Mockito.times(enqueueFails ? 0 : 1)).delete(any(CronJobEntity.class));
        verify(timer).cancel(false);
        verify(scheduler).schedule(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(runtime).runBackground(any(), any(), anyBoolean());

        when(cronJobRepository.findByEnabledTrue()).thenReturn(List.of());
        service.init();
        verify(scheduler).schedule(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), any());
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
