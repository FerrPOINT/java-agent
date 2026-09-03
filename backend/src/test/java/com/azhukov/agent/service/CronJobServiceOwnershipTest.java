package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * rev-22: cron job ownership — a non-admin API key must not be able to
 * update/pause/resume/remove/run jobs owned by another user.
 */
@ExtendWith(MockitoExtension.class)
class CronJobServiceOwnershipTest {

    @Mock private CronJobRepository cronJobRepository;
    @Mock private CronExecutionLogRepository cronExecutionLogRepository;
    @Mock private com.azhukov.agent.persistence.repository.MessageRepository messageRepository;
    @Mock private ObjectProvider<AgentRuntimeService> agentRuntimeServiceProvider;
    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private com.azhukov.agent.core.skill.SkillManager skillManager;

    private AgentProperties properties;
    private CronJobService service;
    private CronJobEntity foreignJob;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCron().setEnabled(false);
        service = new CronJobService(cronJobRepository, agentRuntimeServiceProvider,
            properties, skillManager, cronExecutionLogRepository, messageRepository,
            new org.springframework.transaction.support.TransactionTemplate(),
            new com.azhukov.agent.service.CronScheduleParser());

        foreignJob = new CronJobEntity();
        foreignJob.setId(UUID.randomUUID());
        foreignJob.setUserId("user-other");
        foreignJob.setName("foreign");
        foreignJob.setSchedule("0 9 * * *");
        foreignJob.setPrompt("do stuff");
        foreignJob.setEnabled(true);

        lenient().when(cronJobRepository.findById(foreignJob.getId())).thenReturn(Optional.of(foreignJob));
        lenient().when(cronJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private void loginAs(String userId) {
        UserContext.set(userId, "user");
    }

    @Test
    void nonAdminCannotUpdateForeignJob() {
        loginAs("user-a");
        assertThatThrownBy(() -> service.update(foreignJob.getId(), "hijack", null, null, null, null))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("does not belong");
        verify(cronJobRepository, never()).save(any());
    }

    @Test
    void nonAdminCannotPauseForeignJob() {
        loginAs("user-a");
        assertThatThrownBy(() -> service.pause(foreignJob.getId()))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void nonAdminCannotResumeForeignJob() {
        loginAs("user-a");
        assertThatThrownBy(() -> service.resume(foreignJob.getId()))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void nonAdminCannotRunForeignJob() {
        loginAs("user-a");
        assertThatThrownBy(() -> service.runNow(foreignJob.getId()))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void nonAdminCannotRemoveForeignJob() {
        loginAs("user-a");
        assertThatThrownBy(() -> service.remove(foreignJob.getId()))
            .isInstanceOf(SecurityException.class);
        verify(cronJobRepository, never()).deleteById(any());
    }

    @Test
    void ownerCanUpdateOwnJob() {
        loginAs("user-other");
        assertThatCode(() -> service.update(foreignJob.getId(), "renamed", null, null, null, null))
            .doesNotThrowAnyException();
    }

    @Test
    void legacyJobWithNullUserIdIsAccessible() {
        foreignJob.setUserId(null);
        loginAs("user-a");
        assertThatCode(() -> service.pause(foreignJob.getId()))
            .doesNotThrowAnyException();
    }

    @Test
    void unauthenticatedContextHasFullAccess() {
        // no UserContext set — dev mode / internal scheduler
        assertThatCode(() -> service.pause(foreignJob.getId()))
            .doesNotThrowAnyException();
    }
}
