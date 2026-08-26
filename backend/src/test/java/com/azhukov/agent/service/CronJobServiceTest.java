package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CronExecutionLogEntity;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CronJobServiceTest {

    @Mock private CronJobRepository cronJobRepository;
    @Mock private CronExecutionLogRepository cronExecutionLogRepository;
    @Mock private com.azhukov.agent.persistence.repository.MessageRepository messageRepository;
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
        service = new CronJobService(cronJobRepository, agentRuntimeServiceProvider, properties, skillManager, cronExecutionLogRepository, messageRepository, new org.springframework.transaction.support.TransactionTemplate(), new CronScheduleParser());
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
    void listCronJobs() throws Exception {
        CronJobEntity job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setName("job1");
        when(cronJobRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))).thenReturn(List.of(job));
        List<CronJobEntity> jobs = service.list();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getName()).isEqualTo("job1");
    }

    @Test
    void contextFrom_injectsLatestUpstreamOutput() throws Exception {
        UUID upstreamId = UUID.randomUUID();
        CronJobEntity upstream = new CronJobEntity();
        upstream.setId(upstreamId);
        upstream.setName("collector");
        CronExecutionLogEntity execution = CronExecutionLogEntity.create(
            upstreamId, Instant.now(), Instant.now(), "success", null);
        execution.setOutputText("fresh research output");
        when(cronJobRepository.findById(upstreamId)).thenReturn(Optional.of(upstream));
        when(cronExecutionLogRepository.findFirstByJobIdOrderByStartedAtDesc(upstreamId))
            .thenReturn(Optional.of(execution));

        CronJobEntity target = new CronJobEntity();
        target.setContextFrom(upstreamId.toString());
        Method method = CronJobService.class.getDeclaredMethod("loadContextFromOutput", CronJobEntity.class);
        method.setAccessible(true);
        String context = (String) method.invoke(service, target);

        assertThat(context).contains("Output from job 'collector'");
        assertThat(context).contains("fresh research output");
    }

    @Test
    void contextFrom_self_usesOwnLatestOutput() throws Exception {
        UUID jobId = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(jobId);
        job.setName("continuity");
        job.setContextFrom("self");
        CronExecutionLogEntity execution = CronExecutionLogEntity.create(
            jobId, Instant.now(), Instant.now(), "success", null);
        execution.setOutputText("previous run result");
        when(cronJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(cronExecutionLogRepository.findFirstByJobIdOrderByStartedAtDesc(jobId))
            .thenReturn(Optional.of(execution));

        Method method = CronJobService.class.getDeclaredMethod("loadContextFromOutput", CronJobEntity.class);
        method.setAccessible(true);
        String context = (String) method.invoke(service, job);

        assertThat(context).contains("previous run result");
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
        verify(agentRuntimeService).runBackground("Do something", null, true);
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
        verify(agentRuntimeService).runBackground(org.mockito.ArgumentMatchers.contains("Use this skill"), eq(null), eq(true));
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

        verify(agentRuntimeService).runBackground("Do something plain", null, true);
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

    // ── Fix 1: Full field update tests ──

    @Test
    void updateAllFields() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("old-name");
        job.setSchedule("0 * * * *");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CronJobEntity updated = service.update(
            id, "new-name", "every 1h", "New prompt", "telegram", true,
            "skill1,skill2", "ctx-job-1",
            5,
            "check.sh", true,
            "web,terminal", "/tmp/workdir",
            "openai", "gpt-4", "https://api.openai.com"
        );

        assertThat(updated.getName()).isEqualTo("new-name");
        assertThat(updated.getSchedule()).isEqualTo("every 1h");
        assertThat(updated.getPrompt()).isEqualTo("New prompt");
        assertThat(updated.getDeliverTo()).isEqualTo("telegram");
        assertThat(updated.getSkills()).isEqualTo("skill1,skill2");
        assertThat(updated.getContextFrom()).isEqualTo("ctx-job-1");
        assertThat(updated.getRepeatCount()).isEqualTo(5);
        assertThat(updated.getScript()).isEqualTo("check.sh");
        assertThat(updated.isNoAgent()).isTrue();
        assertThat(updated.getEnabledToolsets()).isEqualTo("web,terminal");
        assertThat(updated.getWorkdir()).isEqualTo("/tmp/workdir");
        assertThat(updated.getModelProvider()).isEqualTo("openai");
        assertThat(updated.getModelName()).isEqualTo("gpt-4");
        assertThat(updated.getBaseUrl()).isEqualTo("https://api.openai.com");
    }

    @Test
    void updateNoAgentRequiresScript() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("0 * * * *");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.update(id, null, null, null, null, null,
                null, null, null, null, true, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("script");
    }

    @Test
    void updateNoAgentWithScriptInSameUpdate() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("0 * * * *");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CronJobEntity updated = service.update(
            id, null, null, null, null, null,
            null, null, null, "monitor.sh", true,
            null, null, null, null, null);

        assertThat(updated.isNoAgent()).isTrue();
        assertThat(updated.getScript()).isEqualTo("monitor.sh");
    }

    @Test
    void updateClearsFieldsWithEmptyString() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("0 * * * *");
        job.setScript("old.sh");
        job.setEnabledToolsets("web,terminal");
        job.setWorkdir("/tmp/old");
        job.setModelProvider("openai");
        job.setModelName("gpt-4");
        job.setBaseUrl("https://old.com");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CronJobEntity updated = service.update(
            id, null, null, null, null, null,
            null, null, null, "", null,
            "", "", "", "", "");

        assertThat(updated.getScript()).isNull();
        assertThat(updated.getEnabledToolsets()).isNull();
        assertThat(updated.getWorkdir()).isNull();
        assertThat(updated.getModelProvider()).isNull();
        assertThat(updated.getModelName()).isNull();
        assertThat(updated.getBaseUrl()).isNull();
    }

    // ── Fix 2: Repeat count tests ──

    @Test
    void createWithRepeatCount() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create(
            "repeat-job", "every 1h", "Run task", null, null, null,
            3, null, false, null, null, null, null, null);
        assertThat(job.getRepeatCount()).isEqualTo(3);
        assertThat(job.getRepeatCompleted()).isEqualTo(0);
    }

    @Test
    void createWithZeroRepeatNormalizesToNull() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create(
            "repeat-job", "every 1h", "Run task", null, null, null,
            0, null, false, null, null, null, null, null);
        assertThat(job.getRepeatCount()).isNull();
    }

    @Test
    void updateRepeatCount() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("every 1h");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CronJobEntity updated = service.update(
            id, null, null, null, null, null,
            null, null, 10, null, null, null, null, null, null, null);

        assertThat(updated.getRepeatCount()).isEqualTo(10);
    }

    @Test
    void updateRepeatCountZeroNormalizesToNull() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("every 1h");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CronJobEntity updated = service.update(
            id, null, null, null, null, null,
            null, null, 0, null, null, null, null, null, null, null);

        assertThat(updated.getRepeatCount()).isNull();
    }

    // ── Fix 3: One-shot schedule tests ──

    @Test
    void createOneShotBareDuration_setsRepeatCount1() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        // "30m" without "every" → one-shot, repeatCount auto-set to 1
        CronJobEntity job = service.create("oneshot", "30m", "Run once", null);
        assertThat(job.getRepeatCount()).isEqualTo(1);
    }

    @Test
    void createOneShotIsoTimestamp_setsRepeatCount1() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        // ISO timestamp → one-shot, repeatCount auto-set to 1
        CronJobEntity job = service.create("oneshot-iso", "2099-12-31T23:59:00", "Run once", null);
        assertThat(job.getRepeatCount()).isEqualTo(1);
    }

    @Test
    void createRecurringInterval_repeatCountNotAutoSet() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        // "every 30m" → recurring, repeatCount stays null (forever)
        CronJobEntity job = service.create("recurring", "every 30m", "Run forever", null);
        assertThat(job.getRepeatCount()).isNull();
    }

    @Test
    void createCronExpression_repeatCountNotAutoSet() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        // "0 9 * * *" → recurring cron, repeatCount stays null
        CronJobEntity job = service.create("cron-job", "0 9 * * *", "Run daily", null);
        assertThat(job.getRepeatCount()).isNull();
    }

    @Test
    void createOneShotWithExplicitRepeatOverridesAutoSet() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        // one-shot with explicit repeat=3 → use 3, not auto-1
        CronJobEntity job = service.create(
            "oneshot-3x", "30m", "Run 3 times", null, null, null,
            3, null, false, null, null, null, null, null);
        assertThat(job.getRepeatCount()).isEqualTo(3);
    }

    @Test
    void validateIsoTimestampSchedule() {
        // Should not throw
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        service.create("iso-job", "2099-06-01T09:00:00", "Run at time", null);
    }

    @Test
    void validateIsoTimestampWithoutSeconds() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        service.create("iso-job", "2099-06-01T09:00", "Run at time", null);
    }

    // ── Fix 4: no_agent mode tests ──

    @Test
    void createNoAgentWithoutScript_throwsException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.create(
                "noagent-job", "every 5m", null, null, null, null,
                null, null, true, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no_agent");
    }

    @Test
    void createNoAgentWithScript_succeeds() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create(
            "watchdog", "every 5m", null, null, null, null,
            null, "monitor.sh", true, null, null, null, null, null);
        assertThat(job.isNoAgent()).isTrue();
        assertThat(job.getScript()).isEqualTo("monitor.sh");
    }

    @Test
    void runNoAgentJob_executesScriptDirectly() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("watchdog");
        job.setEnabled(true);
        job.setNoAgent(true);
        // Use a script that exists on the system — echo via bash
        job.setScript("echo hello");
        // This won't find the file, but it exercises the no_agent path and doesn't call LLM
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.runNow(id);

        // Verify LLM was NOT called
        verify(agentRuntimeService, never()).runBackground(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ── Fix 5/6/7: Override field tests ──

    @Test
    void createWithEnabledToolsets() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create(
            "restricted", "every 1h", "Run task", null, null, null,
            null, null, false, "web,terminal", null, null, null, null);
        assertThat(job.getEnabledToolsets()).isEqualTo("web,terminal");
    }

    @Test
    void createWithWorkdir() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create(
            "workdir-job", "every 1h", "Run task", null, null, null,
            null, null, false, null, "/opt/dev", null, null, null);
        assertThat(job.getWorkdir()).isEqualTo("/opt/dev");
    }

    @Test
    void createWithModelOverrides() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        CronJobEntity job = service.create(
            "override-job", "every 1h", "Run task", null, null, null,
            null, null, false, null, null, "anthropic", "claude-sonnet-4", "https://api.anthropic.com");
        assertThat(job.getModelProvider()).isEqualTo("anthropic");
        assertThat(job.getModelName()).isEqualTo("claude-sonnet-4");
        assertThat(job.getBaseUrl()).isEqualTo("https://api.anthropic.com");
    }

    @Test
    void runNowWithOverrides_appliesRuntimeConstraintsWithoutPromptLeakage() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("override-cron");
        job.setPrompt("Do something");
        job.setEnabled(true);
        job.setEnabledToolsets("web,terminal");
        job.setWorkdir("/opt/dev");
        job.setModelProvider("openai");
        job.setModelName("gpt-4");
        job.setBaseUrl("https://api.openai.com");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.runNow(id);

        verify(agentRuntimeService).runBackground(eq("Do something"), eq(null), eq(true),
            eq(java.util.Map.of("delegation_toolsets", "web,terminal", "cron_workdir", "/opt/dev")));
    }
}