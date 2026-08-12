package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronJobRepository;
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
    @Mock private org.springframework.beans.factory.ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private com.azhukov.agent.core.skill.SkillManager skillManager;

    private AgentProperties properties;
    private CronJobService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCron().setEnabled(true);
        lenient().when(agentRuntimeServiceProvider.getIfAvailable()).thenReturn(agentRuntimeService);
        service = new CronJobService(cronJobRepository, agentRuntimeServiceProvider, properties, skillManager);
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
        // scheduleJob saves the entity with nextRunAt
        verify(cronJobRepository, atLeast(2)).save(any(CronJobEntity.class));
    }
}