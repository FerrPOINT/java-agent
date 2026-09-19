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
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for CronJobService CRUD paths not exercised by the main
 * test: full-featured creates, updates with normalization, lifecycle
 * (pause/resume/remove), validation errors.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CronJobServiceCrudBranchTest {

    @Mock
    private CronJobRepository cronJobRepository;
    @Mock
    private ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    @Mock
    private SkillManager skillManager;
    @Mock
    private CronExecutionLogRepository cronExecutionLogRepository;
    @Mock
    private MessageRepository messageRepository;

    private AgentProperties properties;
    private CronJobService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        service = new CronJobService(
            cronJobRepository,
            agentRuntimeServiceProvider,
            properties,
            skillManager,
            cronExecutionLogRepository,
            messageRepository,
            new org.springframework.transaction.support.TransactionTemplate(),
            new CronScheduleParser());
        lenient().when(cronJobRepository.save(any(CronJobEntity.class))).thenAnswer(inv -> {
            CronJobEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
    }

    private CronJobEntity existing() {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("job");
        entity.setSchedule("0 * * * *");
        entity.setPrompt("prompt");
        entity.setEnabled(true);
        return entity;
    }

    @Test
    void fullCreatePersistsAllOptionalFields() {
        CronJobEntity job = service.create(
            "full", "0 * * * *", "prompt", "telegram:123",
            "skill-a,skill-b", "output_text", 3,
            "script.sh", false,
            "terminal,file", "/tmp",
            "openai-compatible", "gpt-5", "https://api.test",
            "monitor.sh", true, UUID.randomUUID());

        assertThat(job.getName()).isEqualTo("full");
        assertThat(job.getSkills()).isEqualTo("skill-a,skill-b");
        assertThat(job.getContextFrom()).contains("output_text");
        assertThat(job.getScript()).isEqualTo("script.sh");
        assertThat(job.getMonitor()).isEqualTo("monitor.sh");
        assertThat(job.isContinuityEnabled()).isTrue();
        assertThat(job.getAttachedSessionId()).isNotNull();
    }

    @Test
    void createInProfileStampsProfile() {
        CronJobEntity job = service.createInProfile(
            "work", "profiled", "0 * * * *", "prompt", "local",
            null, null, null, null, false,
            null, null, null, null, null);

        assertThat(job.getProfile()).isEqualTo("work");
        assertThat(job.getName()).isEqualTo("profiled");
    }

    @Test
    void createRejectsInvalidSchedule() {
        assertThatThrownBy(() ->
            service.create("bad", "not-a-schedule", "prompt", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateFullFeaturedFieldsPersist() {
        CronJobEntity existing = existing();
        when(cronJobRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        UUID sessionId = UUID.randomUUID();
        CronJobEntity updated = service.update(
            existing.getId(), "renamed", "30 5 * * *", "new prompt", "telegram:1", true,
            "skill-x", "output_text",
            5,
            "new.sh", false,
            "terminal", "/tmp/w",
            "provider-x", "model-x", "https://api2.test",
            "new-monitor.sh", false, true, sessionId);

        assertThat(updated.getName()).isEqualTo("renamed");
        assertThat(updated.getSchedule()).isEqualTo("30 5 * * *");
        assertThat(updated.getAttachedSessionId()).isEqualTo(sessionId);
        assertThat(updated.getMonitor()).isEqualTo("new-monitor.sh");
    }

    @Test
    void updateUnknownJobThrows() {
        UUID id = UUID.randomUUID();
        when(cronJobRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.update(id, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pauseDisablesAndResumeEnables() {
        CronJobEntity existing = existing();
        when(cronJobRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.pause(existing.getId());
        assertThat(existing.isEnabled()).isFalse();

        service.resume(existing.getId());
        assertThat(existing.isEnabled()).isTrue();
    }

    @Test
    void pauseUnknownJobThrows() {
        when(cronJobRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.pause(UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeDeletesEntity() {
        UUID id = UUID.randomUUID();
        when(cronJobRepository.findById(id)).thenReturn(Optional.of(existing()));
        service.remove(id);
        org.mockito.Mockito.verify(cronJobRepository).deleteById(id);
    }

    @Test
    void existsDelegatesToRepository() {
        UUID id = UUID.randomUUID();
        when(cronJobRepository.existsById(id)).thenReturn(true);
        assertThat(service.exists(id)).isTrue();
    }

    @Test
    void listIncludesDisabledWhenRequested() {
        when(cronJobRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))).thenReturn(List.of(existing()));
        assertThat(service.list(true)).hasSize(1);
    }

    @Test
    void needsAttentionFlagsConsecutiveFailures() {
        CronJobEntity failing = existing();
        failing.setConsecutiveFailures(properties.getCron().getNudgeFailureThreshold());
        assertThat(service.needsAttention(failing)).isTrue();

        assertThat(service.needsAttention(existing())).isFalse();
        assertThat(service.needsAttention(null)).isFalse();
    }
}
