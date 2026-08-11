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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CronJobServiceTest {

    @Mock private CronJobRepository cronJobRepository;
    @Mock private org.springframework.beans.factory.ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private com.azhukov.agent.core.skill.SkillManager skillManager;

    private AgentProperties properties;
    private CronJobService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCron().setEnabled(false); // Disable scheduling for tests
        lenient().when(agentRuntimeServiceProvider.getIfAvailable()).thenReturn(agentRuntimeService);
        service = new CronJobService(cronJobRepository, agentRuntimeServiceProvider, properties, skillManager);
    }

    @Test
    void createCronJob() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create("test-job", "0 * * * *", "Run task", null);
        assertThat(job.getName()).isEqualTo("test-job");
        assertThat(job.getSchedule()).isEqualTo("0 * * * *");
        assertThat(job.getPrompt()).isEqualTo("Run task");
        assertThat(job.isEnabled()).isTrue();
        verify(cronJobRepository).save(any());
    }

    @Test
    void listCronJobs() {
        CronJobEntity job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setName("job1");
        when(cronJobRepository.findAll()).thenReturn(List.of(job));
        List<CronJobEntity> jobs = service.list();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getName()).isEqualTo("job1");
    }

    @Test
    void pauseCronJob() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setEnabled(true);
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CronJobEntity paused = service.pause(id);
        assertThat(paused.isEnabled()).isFalse();
        verify(cronJobRepository).save(any());
    }

    @Test
    void resumeCronJob() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setEnabled(false);
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CronJobEntity resumed = service.resume(id);
        assertThat(resumed.isEnabled()).isTrue();
        verify(cronJobRepository).save(any());
    }

    @Test
    void removeCronJob() {
        UUID id = UUID.randomUUID();
        service.remove(id);
        verify(cronJobRepository).deleteById(id);
    }

    @Test
    void runNowExecutesJob() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setPrompt("Do something");
        job.setEnabled(true);
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.runNow(id);
        verify(agentRuntimeService).runBackground("Do something", null);
    }

    @Test
    void updateCronJob() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("old-name");
        job.setSchedule("0 * * * *");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CronJobEntity updated = service.update(id, "new-name", "0 0 * * *", "New prompt", null, true);
        assertThat(updated.getName()).isEqualTo("new-name");
        assertThat(updated.getSchedule()).isEqualTo("0 0 * * *");
        assertThat(updated.getPrompt()).isEqualTo("New prompt");
    }

    @Test
    void findByName() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("find-me");
        when(cronJobRepository.findByName("find-me")).thenReturn(Optional.of(job));
        Optional<CronJobEntity> found = service.findByName("find-me");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("find-me");
    }

    @Test
    void createWithSkills_setsSkillsField() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create("skill-job", "0 * * * *", "Run task", null, "hermes-agent,backend-dev");
        assertThat(job.getSkills()).isEqualTo("hermes-agent,backend-dev");
    }

    @Test
    void runNowWithSkills_loadsAndInjectsSkillContent() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("skill-cron");
        job.setPrompt("Do something");
        job.setEnabled(true);
        job.setSkills("hermes-agent,backend-dev");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(skillManager.getSkill("hermes-agent")).thenReturn("# Hermes Agent\nUse this skill.");
        when(skillManager.getSkill("backend-dev")).thenReturn("# Backend Dev\nDev conventions.");

        service.runNow(id);

        // Verify skills were loaded
        verify(skillManager).getSkill("hermes-agent");
        verify(skillManager).getSkill("backend-dev");
        // Verify the enhanced prompt (with skills injected) was passed to runtime
        verify(agentRuntimeService).runBackground(org.mockito.ArgumentMatchers.contains("Use this skill"), eq(null));
    }

    @Test
    void runNowWithoutSkills_passesOriginalPrompt() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("no-skill-cron");
        job.setPrompt("Do something plain");
        job.setEnabled(true);
        job.setSkills(null);
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.runNow(id);

        verify(agentRuntimeService).runBackground("Do something plain", null);
        verifyNoInteractions(skillManager);
    }

    // ── Human-readable interval tests ──

    @Test
    void createWithHumanReadableInterval_5m() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create("interval-job", "5m", "Run task", null);
        assertThat(job.getSchedule()).isEqualTo("5m");
        assertThat(job.isEnabled()).isTrue();
    }

    @Test
    void createWithHumanReadableInterval_every2h() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create("interval-job", "every 2h", "Run task", null);
        assertThat(job.getSchedule()).isEqualTo("every 2h");
    }

    @Test
    void createWithHumanReadableInterval_30s() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create("interval-job", "30s", "Run task", null);
        assertThat(job.getSchedule()).isEqualTo("30s");
    }

    @Test
    void createWithHumanReadableInterval_1d() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create("interval-job", "1d", "Run task", null);
        assertThat(job.getSchedule()).isEqualTo("1d");
    }

    @Test
    void createWithInvalidSchedule_throwsException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.create("bad-job", "not-a-schedule", "Run task", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid");
    }

    @Test
    void createWithNullSchedule_throwsException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.create("null-schedule", null, "Run task", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createWithBlankSchedule_throwsException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.create("blank-schedule", "  ", "Run task", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateWithHumanReadableInterval() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("old-name");
        job.setSchedule("0 * * * *");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CronJobEntity updated = service.update(id, "new-name", "10m", "New prompt", null, true);
        assertThat(updated.getSchedule()).isEqualTo("10m");
    }
}