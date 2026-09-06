package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage + regression for uncovered CronJobService branches: init re-arm of
 * stale error jobs, create overloads, ownership stamping, monitor/continuity
 * rejection, schedule variants via the full-arg create path.
 */
@ExtendWith(MockitoExtension.class)
class CronJobServiceBranchTest {

    @Mock private CronJobRepository cronJobRepository;
    @Mock private CronExecutionLogRepository cronExecutionLogRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private SkillManager skillManager;

    private AgentProperties properties;
    private CronJobService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCron().setEnabled(false);
        lenient().when(agentRuntimeServiceProvider.getIfAvailable()).thenReturn(agentRuntimeService);
        lenient().when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new CronJobService(cronJobRepository, agentRuntimeServiceProvider, properties,
            skillManager, cronExecutionLogRepository, messageRepository,
            new org.springframework.transaction.support.TransactionTemplate(), new CronScheduleParser());
    }

    @Test
    void initReArmsJobsStuckInErrorState() {
        CronJobEntity stuck = new CronJobEntity();
        stuck.setId(UUID.randomUUID());
        stuck.setName("stuck");
        stuck.setEnabled(true);
        stuck.setSchedule("*/5 * * * *");
        stuck.setPrompt("tick");
        stuck.setLastStatus("error");
        stuck.setLastError("boom");
        stuck.setConsecutiveFailures(3);

        when(cronJobRepository.findByEnabledTrue()).thenReturn(List.of(stuck));
        properties.getCron().setEnabled(true);
        try {
            service.init();
        } finally {
            service.shutdown(); // daemon scheduler — release it right away
        }

        assertThat(stuck.getLastStatus()).isNull();
        assertThat(stuck.getLastError()).isNull();
        assertThat(stuck.getConsecutiveFailures()).isZero();
        verify(cronJobRepository, org.mockito.Mockito.atLeastOnce()).save(stuck);
    }

    @Test
    void createOverloadsDelegateToFullCreate() {
        CronJobEntity e1 = service.create("j1", "*/5 * * * *", "p", "local");
        assertThat(e1.getName()).isEqualTo("j1");
        assertThat(e1.getSchedule()).isEqualTo("*/5 * * * *");
        assertThat(e1.getPrompt()).isEqualTo("p");

        CronJobEntity e2 = service.create("j2", "*/5 * * * *", "p", "local", "sk");
        assertThat(e2.getSkills()).isEqualTo("sk");

        CronJobEntity e3 = service.create("j3", "*/5 * * * *", "p", "local", "sk", null);
        assertThat(e3.getName()).isEqualTo("j3");

        CronJobEntity e4 = service.createInProfile("dev", "j4", "*/5 * * * *", "p", "local", "sk");
        assertThat(e4.getName()).isEqualTo("j4");

        CronJobEntity e5 = service.createInProfile("dev", "j5", "*/5 * * * *", "p", "local", "sk", null);
        assertThat(e5.getName()).isEqualTo("j5");
    }

    @Test
    void fullCreatePersistsAllHermesParityFields() {
        CronJobEntity e = service.create("full", "0 9 * * *", "prompt", "local",
            "sk1,sk2", "upstream-1", 5, "echo hi", true, "web,terminal", "/tmp",
            "openai", "gpt-4o", "https://x.example");

        assertThat(e.getSkills()).isEqualTo("sk1,sk2");
        assertThat(e.getContextFrom()).isEqualTo("upstream-1");
        assertThat(e.getRepeatCount()).isEqualTo(5);
        assertThat(e.getScript()).isEqualTo("echo hi");
        assertThat(e.isNoAgent()).isTrue();
        assertThat(e.getEnabledToolsets()).isEqualTo("web,terminal");
        assertThat(e.getWorkdir()).isEqualTo("/tmp");
        assertThat(e.getModelProvider()).isEqualTo("openai");
        assertThat(e.getModelName()).isEqualTo("gpt-4o");
        assertThat(e.getBaseUrl()).isEqualTo("https://x.example");
        assertThat(e.getCreatedAt()).isNotNull();
    }

    @Test
    void fullCreateRejectsInvalidSchedule() {
        assertThatThrownBy(() -> service.create("bad", "not a schedule", "p", "local",
            null, null, null, null, false, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsNoAgentWithoutScript() {
        // no_agent=true requires a script — enforced at create time
        assertThatThrownBy(() -> service.create("na", "*/5 * * * *", null, "local",
            null, null, null, null, true, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no_agent=true requires a script");
    }

    @Test
    void createRejectsBlankPayload() {
        // agent job with no prompt, no script, no skills
        assertThatThrownBy(() -> service.create("blank", "*/5 * * * *", "  ", "local",
            null, null, null, null, false, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nothing to run");
    }

    @Test
    void runNowUnknownJobFails() {
        UUID id = UUID.randomUUID();
        when(cronJobRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.runNow(id))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void pauseResumeRemoveTouchRepository() {
        CronJobEntity job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setName("pr");
        job.setEnabled(true);
        job.setSchedule("*/5 * * * *");
        job.setPrompt("p");
        when(cronJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        CronJobEntity paused = service.pause(job.getId());
        assertThat(paused.isEnabled()).isFalse();

        CronJobEntity resumed = service.resume(job.getId());
        assertThat(resumed.isEnabled()).isTrue();

        service.remove(job.getId());
        verify(cronJobRepository).deleteById(job.getId());
    }

    @Test
    void findByNameAndListReturnRepositoryResults() {
        CronJobEntity job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setName("finder");
        job.setSchedule("*/5 * * * *");
        job.setPrompt("p");
        when(cronJobRepository.findByName("finder")).thenReturn(Optional.of(job));
        assertThat(service.findByName("finder")).contains(job);
        when(cronJobRepository.findByNameAndProfile("finder", "dev")).thenReturn(Optional.of(job));
        assertThat(service.findByName("finder", "dev")).contains(job);
    }
}
