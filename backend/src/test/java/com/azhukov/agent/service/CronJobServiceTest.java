package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    @Mock private org.springframework.beans.factory.ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private com.azhukov.agent.core.skill.SkillManager skillManager;

    @TempDir
    private Path tempDir;

    private AgentProperties properties;
    private CronJobService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCron().setEnabled(false); // Disable scheduling for tests
        lenient().when(agentRuntimeServiceProvider.getIfAvailable()).thenReturn(agentRuntimeService);
        service = new CronJobService(cronJobRepository, agentRuntimeServiceProvider, properties, skillManager, cronExecutionLogRepository, new org.springframework.transaction.support.TransactionTemplate());
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
        assertThat(job.getProfile()).isEqualTo("default");
        assertThat(job.getSchedule()).isEqualTo("0 * * * *");
        assertThat(job.getPrompt()).isEqualTo("Run task");
        assertThat(job.isEnabled()).isTrue();
        verify(cronJobRepository).save(any());
    }

    @Test
    void createInProfilePersistsHermesProfileScope() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        CronJobEntity job = service.createInProfile("Work", "test-job", "0 * * * *", "Run task", null, "research");

        assertThat(job.getProfile()).isEqualTo("work");
        assertThat(job.getSkills()).isEqualTo("research");
        verify(cronJobRepository).save(any());
    }

    @Test
    void listCronJobs() {
        CronJobEntity job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setName("job1");
        when(cronJobRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))).thenReturn(List.of(job));
        List<CronJobEntity> jobs = service.list();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getName()).isEqualTo("job1");
    }

    @Test
    void listForProfileUsesProfileAwareRepositoryMethods() {
        CronJobEntity job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setName("job1");
        job.setProfile("work");
        when(cronJobRepository.findByProfileAndEnabledTrue(eq("work"), any(org.springframework.data.domain.Sort.class)))
            .thenReturn(List.of(job));

        List<CronJobEntity> jobs = service.listForProfile("Work", false);

        assertThat(jobs).containsExactly(job);
        verify(cronJobRepository).findByProfileAndEnabledTrue(eq("work"), any(org.springframework.data.domain.Sort.class));
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
    void runNowWithExtraPromptAppendsTransientContextWithoutPersistingIt() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setPrompt("Do something");
        job.setEnabled(true);
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.runNow(id, "Use fresh context");

        verify(agentRuntimeService).runBackground("Do something\n\n---\n\nUse fresh context", null, true);
        assertThat(job.getPrompt()).isEqualTo("Do something");
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

    @Test
    void runNowForNamedProfilePassesProfileMetadataToRuntime() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("profile-cron");
        job.setProfile("work");
        job.setPrompt("Do something plain");
        job.setEnabled(true);
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        EventService eventService = new EventService(10);
        service.setEventService(eventService);

        service.runNow(id);

        verify(agentRuntimeService).runBackground("Do something plain", null, true, Map.of("profile", "work"));
        assertThat(eventService.replay("work", 0L, 10))
            .extracting(EventService.EventEnvelope::type)
            .containsExactly("cron.started", "cron.success");
        assertThat(eventService.replay("default", 0L, 10)).isEmpty();
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
    void createUnpinnedAgentJobCapturesCurrentProviderAndModelSnapshots() {
        properties.getModel().setProvider("OpenRouter");
        properties.getModel().setModelName("old/model");
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        CronJobEntity job = service.create(
            "agent-job", "every 5m", "Run task", null, null, null,
            null, null, false, null, null, null, null, null);

        assertThat(job.getProviderSnapshot()).isEqualTo("openrouter");
        assertThat(job.getModelSnapshot()).isEqualTo("old/model");
        assertThat(job.getModelProvider()).isNull();
        assertThat(job.getModelName()).isNull();
    }

    @Test
    void createPinnedAgentJobOnlySnapshotsUnpinnedAxes() {
        properties.getModel().setProvider("openrouter");
        properties.getModel().setModelName("old/model");
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        CronJobEntity job = service.create(
            "agent-job", "every 5m", "Run task", null, null, null,
            null, null, false, null, null, "nous", null, null);

        assertThat(job.getProviderSnapshot()).isNull();
        assertThat(job.getModelSnapshot()).isEqualTo("old/model");
    }

    @Test
    void createInProfileCapturesProfileLocalModelSnapshots() throws Exception {
        AgentProperties profileProperties = new AgentProperties();
        profileProperties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        ProfileService profileService = new ProfileService(profileProperties, new RuntimeConfigService());
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "worker", null, false, false, true, null, null, null, null));
        profileService.writeModel("worker", "nous", "worker/model", null);
        service.setProfileService(profileService);
        properties.getModel().setProvider("global-provider");
        properties.getModel().setModelName("global/model");
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        CronJobEntity job = service.createInProfile(
            "worker", "agent-job", "every 5m", "Run task", null, null, null,
            null, null, false, null, null, null, null, null);

        assertThat(job.getProviderSnapshot()).isEqualTo("nous");
        assertThat(job.getModelSnapshot()).isEqualTo("worker/model");
    }

    @Test
    void createRejectsAbsoluteScriptPath() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.create(
                "bad-script", "every 5m", "Run task", null, null, null,
                null, "/tmp/run.sh", false, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("relative to ~/.hermes/scripts");

        verify(cronJobRepository, never()).save(any());
    }

    @Test
    void createRejectsTraversalScriptPath() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.create(
                "bad-script", "every 5m", "Run task", null, null, null,
                null, "../run.sh", false, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("traversal");

        verify(cronJobRepository, never()).save(any());
    }

    @Test
    void createNormalizesRelativeScriptPath() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        CronJobEntity job = service.create(
            "script-job", "every 5m", "Run task", null, null, null,
            null, " scripts\\daily.py ", false, null, null, null, null, null);

        assertThat(job.getScript()).isEqualTo("scripts/daily.py");
    }

    @Test
    void createAllowsSkillsOnlyPayload() {
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        CronJobEntity job = service.create(
            "skills-job", "every 5m", null, null, "coding", null,
            null, null, false, null, null, null, null, null);

        assertThat(job.getPrompt()).isNull();
        assertThat(job.getSkills()).isEqualTo("coding");
    }

    @Test
    void createWithMonitorContinuityAndAttachStoresHermesFields() {
        UUID attachedSessionId = UUID.fromString("33333333-4444-5555-6666-777777777777");
        when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        CronJobEntity job = service.create(
            "watch", "every 5m", "Summarize changes", null, null, "upstream",
            null, null, false, null, null, null, null, null,
            "checks/state.py", true, attachedSessionId);

        assertThat(job.getMonitor()).isEqualTo("checks/state.py");
        assertThat(job.isContinuityEnabled()).isTrue();
        assertThat(job.getContextFrom()).isEqualTo("self,upstream");
        assertThat(job.getAttachedSessionId()).isEqualTo(attachedSessionId);
    }

    @Test
    void createRejectsMonitorWithNoAgent() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(
                "watch", "every 5m", "Summarize changes", null, null, null,
                null, "job.py", true, null, null, null, null, null,
                "checks/state.py", false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("monitor jobs cannot use no_agent");

        verify(cronJobRepository, never()).save(any());
    }

    @Test
    void updateContinuityAddsAndRemovesSelfContext() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("watch");
        job.setSchedule("every 5m");
        job.setPrompt("Run task");
        job.setContextFrom("upstream");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CronJobEntity enabled = service.update(
            id, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, true, null, null);
        assertThat(enabled.isContinuityEnabled()).isTrue();
        assertThat(enabled.getContextFrom()).isEqualTo("self,upstream");

        CronJobEntity disabled = service.update(
            id, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, false, null, null);
        assertThat(disabled.isContinuityEnabled()).isFalse();
        assertThat(disabled.getContextFrom()).isEqualTo("upstream");
    }

    @Test
    void runNowWithUnchangedMonitorSkipsAgentRuntime() throws Exception {
        properties.getSecurity().setUrlSafetyEnabled(false);
        com.sun.net.httpserver.HttpServer server = monitorServer("version=1\n");
        try {
            UUID id = UUID.randomUUID();
            CronJobEntity job = new CronJobEntity();
            job.setId(id);
            job.setName("watch");
            job.setPrompt("Summarize changes");
            job.setEnabled(true);
            job.setMonitor(monitorUrl(server));
            job.setMonitorLastHash(sha256("version=1\n"));
            job.setMonitorLastOutput("version=1\n");
            when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
            when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.runNow(id);

            verify(agentRuntimeService, never()).runBackground(any(), any(), anyBoolean());
            assertThat(job.getLastStatus()).isEqualTo("no_change");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runNowWithChangedMonitorInjectsContextAndStoresHash() throws Exception {
        properties.getSecurity().setUrlSafetyEnabled(false);
        com.sun.net.httpserver.HttpServer server = monitorServer("version=2\n");
        try {
            UUID id = UUID.randomUUID();
            CronJobEntity job = new CronJobEntity();
            job.setId(id);
            job.setName("watch");
            job.setPrompt("Summarize changes");
            job.setEnabled(true);
            job.setMonitor(monitorUrl(server));
            job.setMonitorLastHash(sha256("version=1\n"));
            job.setMonitorLastOutput("version=1\n");
            when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
            when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.runNow(id);

            verify(agentRuntimeService).runBackground(
                org.mockito.ArgumentMatchers.contains("Cron monitor changed"), eq(null), eq(true));
            assertThat(job.getMonitorLastHash()).isEqualTo(sha256("version=2\n"));
            assertThat(job.getMonitorLastOutput()).isEqualTo("version=2\n");
            assertThat(job.getMonitorLastChangedAt()).isNotNull();
            assertThat(job.getLastStatus()).isEqualTo("success");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scheduledFailureDoesNotConsumeRepeatOrDeleteJob() throws Exception {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("repeat-watch");
        job.setSchedule("every 1h");
        job.setPrompt("Run task");
        job.setEnabled(true);
        job.setRepeatCount(1);
        job.setRepeatCompleted(0);
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentRuntimeService.runBackground(any(), any(), anyBoolean()))
            .thenThrow(new RuntimeException("backend exploded"));

        java.lang.reflect.Method method = CronJobService.class.getDeclaredMethod("executeAndReschedule", UUID.class);
        method.setAccessible(true);
        method.invoke(service, id);

        assertThat(job.getLastStatus()).isEqualTo("error");
        assertThat(job.getLastError()).contains("backend exploded");
        assertThat(job.getConsecutiveFailures()).isEqualTo(1);
        assertThat(job.getRepeatCompleted()).isZero();
        verify(cronJobRepository, never()).deleteById(id);
    }

    @Test
    void createRejectsEmptyRunnablePayload() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.create(
                "empty-job", "every 5m", " ", null, " ", null,
                null, null, false, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nothing to run");

        verify(cronJobRepository, never()).save(any());
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
        job.setProviderSnapshot("openrouter");
        job.setModelSnapshot("old/model");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CronJobEntity updated = service.update(
            id, null, null, null, null, null,
            null, null, null, "monitor.sh", true,
            null, null, null, null, null);

        assertThat(updated.isNoAgent()).isTrue();
        assertThat(updated.getScript()).isEqualTo("monitor.sh");
        assertThat(updated.getProviderSnapshot()).isNull();
        assertThat(updated.getModelSnapshot()).isNull();
    }

    @Test
    void updateWithUnchangedNoAgentAndNameKeepsExistingSnapshots() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("0 * * * *");
        job.setPrompt("Run task");
        job.setNoAgent(false);
        job.setProviderSnapshot("initial-provider");
        job.setModelSnapshot("old/model");
        properties.getModel().setProvider("changed-provider");
        properties.getModel().setModelName("new/model");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CronJobEntity updated = service.update(
            id, "renamed", null, null, null, null,
            null, null, null, null, false,
            null, null, null, null, null);

        assertThat(updated.getName()).isEqualTo("renamed");
        assertThat(updated.getProviderSnapshot()).isEqualTo("initial-provider");
        assertThat(updated.getModelSnapshot()).isEqualTo("old/model");
    }

    @Test
    void updateRejectsAbsoluteScriptPath() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("0 * * * *");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.update(
                id, null, null, null, null, null,
                null, null, null, "/tmp/run.sh", null,
                null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("relative to ~/.hermes/scripts");

        verify(cronJobRepository, never()).save(any());
    }

    @Test
    void updateRejectsTraversalScriptPath() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("0 * * * *");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.update(
                id, null, null, null, null, null,
                null, null, null, "../run.sh", null,
                null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("traversal");

        verify(cronJobRepository, never()).save(any());
    }

    @Test
    void updateNoAgentWithBlankScriptStillFails() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("0 * * * *");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.update(
                id, null, null, null, null, null,
                null, null, null, " ", true,
                null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("script");

        verify(cronJobRepository, never()).save(any());
    }

    @Test
    void updateClearsFieldsWithEmptyString() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("0 * * * *");
        job.setPrompt("Run task");
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

    @Test
    void updateRejectsClearingLastRunnablePayload() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("test-job");
        job.setSchedule("0 * * * *");
        job.setPrompt("Run task");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            service.update(
                id, null, null, " ", null, null,
                "", null, null, null, null,
                null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nothing to run");

        verify(cronJobRepository, never()).save(any());
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
        // Legacy missing script exercises the no_agent path and must not call the LLM.
        job.setScript("missing.py");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.runNow(id);

        // Verify LLM was NOT called
        verify(agentRuntimeService, never()).runBackground(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(job.getLastStatus()).isEqualTo("error");
        assertThat(job.getLastError()).contains("Script not found");
    }

    @Test
    void runNoAgentJob_blocksLegacyAbsoluteScriptPathAsFailure() {
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("watchdog");
        job.setEnabled(true);
        job.setNoAgent(true);
        job.setScript("/tmp/run.sh");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.runNow(id);

        verify(agentRuntimeService, never()).runBackground(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(job.getLastStatus()).isEqualTo("error");
        assertThat(job.getLastError()).contains("relative to ~/.hermes/scripts");
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

    @Test
    void runNowSkipsUnpinnedAgentJobWhenModelSnapshotDrifts() {
        properties.getModel().setProvider("nous");
        properties.getModel().setModelName("new/model");
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("drift-cron");
        job.setPrompt("Do something");
        job.setEnabled(true);
        job.setProviderSnapshot("openrouter");
        job.setModelSnapshot("old/model");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CronJobEntity updated = service.runNow(id);

        assertThat(updated.getLastStatus()).isEqualTo("error");
        assertThat(updated.getLastError())
            .contains("Skipped to prevent unintended spend")
            .contains("provider 'openrouter' -> 'nous'")
            .contains("model 'old/model' -> 'new/model'");
        verifyNoInteractions(agentRuntimeService);
    }

    @Test
    void runNowAllowsDriftWhenCronModelDriftGuardIsExplicitlyFalse() throws Exception {
        AgentProperties profileProperties = new AgentProperties();
        profileProperties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        ProfileService profileService = new ProfileService(profileProperties, new RuntimeConfigService());
        profileService.writeConfig("default", Map.of("cron", Map.of("model_drift_guard", false)));
        service.setProfileService(profileService);
        properties.getModel().setProvider("nous");
        properties.getModel().setModelName("new/model");
        UUID id = UUID.randomUUID();
        CronJobEntity job = new CronJobEntity();
        job.setId(id);
        job.setName("drift-cron");
        job.setPrompt("Do something");
        job.setEnabled(true);
        job.setProviderSnapshot("openrouter");
        job.setModelSnapshot("old/model");
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(job));
        when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.runNow(id);

        verify(agentRuntimeService).runBackground("Do something", null, true);
        assertThat(job.getLastStatus()).isEqualTo("success");
    }

    private static com.sun.net.httpserver.HttpServer monitorServer(String body) throws Exception {
        com.sun.net.httpserver.HttpServer server =
            com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/state", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static String monitorUrl(com.sun.net.httpserver.HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/state";
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
