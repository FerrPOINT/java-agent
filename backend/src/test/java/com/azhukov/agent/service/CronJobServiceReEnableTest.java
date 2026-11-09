package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * REM-9: Verify that cron jobs are properly loaded on init (no 50-item limit),
 * and that disabled→re-enabled jobs get rescheduled.
 */
@ExtendWith(MockitoExtension.class)
class CronJobServiceReEnableTest {

    @Mock private CronJobRepository cronJobRepository;
    @Mock private CronExecutionLogRepository cronExecutionLogRepository;
    @Mock private com.azhukov.agent.persistence.repository.MessageRepository messageRepository;
    @Mock private org.springframework.beans.factory.ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private com.azhukov.agent.core.skill.SkillManager skillManager;

    private AgentProperties properties;
    private CronJobService service;

    @Test
    void cronUsesTheExactNextTickAcrossFractionalSecondsAndDst() {
        var parser = new CronScheduleParser();
        var utc = java.time.ZoneOffset.UTC;
        assertThat(parser.parse("* * * * *", Instant.parse("2026-11-09T12:00:00.500Z"), utc).nextRunAt())
            .isEqualTo(Instant.parse("2026-11-09T12:01:00Z"));
        assertThat(parser.parse("2026-11-09T12:00:00.900Z", Instant.parse("2026-11-09T12:00:00.500Z"), utc).nextRunAt())
            .isEqualTo(Instant.parse("2026-11-09T12:00:00.900Z"));
        var berlin = java.time.ZoneId.of("Europe/Berlin");
        assertThat(parser.parse("0 9 * * *", Instant.parse("2026-03-28T09:00:00Z"), berlin).nextRunAt())
            .isEqualTo(Instant.parse("2026-03-29T07:00:00Z"));
    }

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(cronJobRepository.findByIdForUpdate(
                org.mockito.ArgumentMatchers.any(java.util.UUID.class)))
            .thenAnswer(call -> cronJobRepository.findById(call.getArgument(0)));
        properties = new AgentProperties();
        properties.getCron().setEnabled(true);
        lenient().when(agentRuntimeServiceProvider.getIfAvailable()).thenReturn(agentRuntimeService);
        service = new CronJobService(cronJobRepository, agentRuntimeServiceProvider, properties, skillManager, cronExecutionLogRepository, messageRepository, CronJobServiceTest.unitTransaction(), new CronScheduleParser());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,true", "true,false"})
    void restartOnlyReplaysOverdueOneShot(boolean overdue, boolean oneShot) {
        var job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setEnabled(true);
        job.setSchedule(oneShot ? "30m" : "every 30m");
        if (oneShot) job.setRepeatCount(1);
        Instant due = Instant.now().plusSeconds(overdue ? -60 : 300);
        job.setNextRunAt(due);
        when(cronJobRepository.findByEnabledTrue()).thenReturn(List.of(job));
        when(cronJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        var scheduler = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        var timer = org.mockito.Mockito.mock(java.util.concurrent.ScheduledFuture.class);
        org.mockito.Mockito.doReturn(timer).when(scheduler)
            .schedule(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), any());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scheduler", scheduler);
        service.init();
        var delay = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(scheduler).schedule(any(Runnable.class), delay.capture(), org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.MILLISECONDS));
        if (overdue && !oneShot) {
            assertThat(job.getNextRunAt()).isAfter(Instant.now());
            assertThat(delay.getValue()).isBetween(1_780_000L, 1_800_000L);
        } else {
            assertThat(job.getNextRunAt()).isEqualTo(due);
            if (overdue) assertThat(delay.getValue()).isZero();
            else assertThat(delay.getValue()).isBetween(280_000L, 300_000L);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"99 9 * * *", "0 0 31 2 *"})
    void invalidScheduleDisablesOnlyThatJobOnStartupAndCannotBeResumed(String schedule) {
        var invalid = new CronJobEntity();
        invalid.setId(UUID.randomUUID());
        invalid.setEnabled(true);
        invalid.setSchedule(schedule);
        invalid.setNextRunAt(Instant.now().plusSeconds(300));
        var valid = new CronJobEntity();
        valid.setId(UUID.randomUUID());
        valid.setEnabled(true);
        valid.setSchedule("0 9 * * *");
        when(cronJobRepository.findByEnabledTrue()).thenReturn(List.of(invalid, valid));
        when(cronJobRepository.findById(invalid.getId())).thenReturn(Optional.of(invalid));
        when(cronJobRepository.findById(valid.getId())).thenReturn(Optional.of(valid));

        try {
            service.init();
            assertThat(invalid.isEnabled()).isFalse();
            assertThat(invalid.getNextRunAt()).isNull();
            assertThat(invalid.getLastError()).isNotBlank();
            assertThat(valid.getNextRunAt()).isAfter(Instant.now());
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resume(invalid.getId()));
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> service.update(valid.getId(), "bad edit", schedule, null, null, null));
            assertThat(valid.getSchedule()).isEqualTo("0 9 * * *");
            assertThat(valid.getName()).isNull();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void deletedJobBeforeManualCallbackDoesNotKeepAnExecutionLock() {
        var job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        when(cronJobRepository.findById(job.getId())).thenReturn(Optional.of(job), Optional.empty());
        var scheduler = mock(java.util.concurrent.ScheduledExecutorService.class);
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scheduler", scheduler);
        service.runNowBackground(job.getId(), null);
        verify(scheduler).execute(callback.capture());
        callback.getValue().run();
        var locks = (java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "jobLocks");
        assertThat(locks).isEmpty();
    }

    @Test
    void init_loadsAllEnabledJobs_noPaginationLimit() {
        // Create more than 50 enabled jobs to verify no pagination limit
        List<CronJobEntity> jobs = new ArrayList<>();
        for (int i = 0; i < 75; i++) {
            CronJobEntity job = new CronJobEntity();
            job.setId(UUID.randomUUID());
            job.setName("job-" + i);
            job.setSchedule("1h");
            job.setPrompt("test prompt");
            job.setEnabled(true);
            job.setCreatedAt(Instant.now());
            jobs.add(job);
        }
        when(cronJobRepository.findByEnabledTrue()).thenReturn(jobs);
        when(cronJobRepository.findById(any(UUID.class))).thenAnswer(invocation ->
            jobs.stream().filter(job -> job.getId().equals(invocation.getArgument(0))).findFirst());
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.init();

        // Verify all 75 jobs were loaded (not limited to 50)
        verify(cronJobRepository).findByEnabledTrue();
        // Each job should have been saved during scheduleJob (to set nextRunAt)
        verify(cronJobRepository, atLeast(75)).save(any(CronJobEntity.class));
    }

    @Test
    void update_reEnablingDisabledJob_reschedulesIt() {
        UUID id = UUID.randomUUID();
        CronJobEntity disabledJob = new CronJobEntity();
        disabledJob.setId(id);
        disabledJob.setName("disabled-job");
        disabledJob.setSchedule("1h");
        disabledJob.setPrompt("Do stuff");
        disabledJob.setEnabled(false);
        disabledJob.setCreatedAt(Instant.now());

        when(cronJobRepository.findById(id)).thenReturn(Optional.of(disabledJob));
        when(cronJobRepository.save(any())).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            return e;
        });

        // Re-enable via update
        CronJobEntity result = service.update(id, null, null, null, null, true);

        assertThat(result.isEnabled()).isTrue();
        // The job should have been saved (with nextRunAt set by scheduleJob)
        // scheduleJob calls cronJobRepository.save to persist nextRunAt
        verify(cronJobRepository, atLeast(2)).save(any(CronJobEntity.class));
    }

    @Test
    void resume_schedulesJob() {
        UUID id = UUID.randomUUID();
        CronJobEntity disabledJob = new CronJobEntity();
        disabledJob.setId(id);
        disabledJob.setName("paused-job");
        disabledJob.setSchedule("1h");
        disabledJob.setPrompt("Do stuff");
        disabledJob.setEnabled(false);
        disabledJob.setCreatedAt(Instant.now());

        when(cronJobRepository.findById(id)).thenReturn(Optional.of(disabledJob));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CronJobEntity result = service.resume(id);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getNextRunAt()).isNotNull();
        verify(cronJobRepository).save(result);
    }
}