package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import com.azhukov.agent.persistence.repository.CronJobRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Cron row visibility also governs mutations; an attached conversation shares its persisted owner. */
class CronJobOwnershipTest {
    private final CronJobRepository jobs = mock(CronJobRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final CronExecutionLogRepository executions = mock(CronExecutionLogRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private CronJobService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var properties = new AgentProperties();
        properties.getCron().setEnabled(false);
        service = new CronJobService(jobs, mock(ObjectProvider.class), properties,
            mock(SkillManager.class), executions, messages,
            CronJobServiceTest.unitTransaction(), new CronScheduleParser());
        ObjectProvider<SessionRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sessions);
        ReflectionTestUtils.setField(service, "sessionRepositoryProvider", provider);
        when(jobs.save(any(CronJobEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearIdentity() {
        UserContext.clear();
        service.shutdown();
    }

    @ParameterizedTest
    @CsvSource({
        "pause,", "resume,", "update,", "remove,", "run,", "background,",
        "pause,foreign", "resume,foreign", "update,foreign", "remove,foreign", "run,foreign", "background,foreign"
    })
    void ordinaryUserCannotMutateUnattributedOrForeignJob(String operation, String owner) {
        UserContext.set("owner", UserContext.ROLE_USER);
        var job = job(owner);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> {
            switch (operation) {
                case "pause" -> service.pause(job.getId());
                case "resume" -> service.resume(job.getId());
                case "update" -> service.update(job.getId(), "changed", null, null, null, null);
                case "remove" -> service.remove(job.getId());
                case "run" -> service.runNow(job.getId());
                case "background" -> service.runNowBackground(job.getId(), null);
                default -> throw new AssertionError(operation);
            }
        }).isInstanceOf(SecurityException.class);
        assertThat(job.getName()).isEqualTo("original");
        assertThat(job.isEnabled()).isTrue();
        verify(jobs, never()).save(any());
        verify(jobs, never()).delete(any());
        verify(jobs, never()).recordRun(any());
        verifyNoInteractions(executions, messages);
    }

    @ParameterizedTest
    @CsvSource({"user,owner", "admin,owner", "admin,foreign", "admin,"})
    void ownerAndAdministratorCanStillPauseTheirVisibleJob(String role, String owner) {
        UserContext.set("owner", role);
        var job = job(owner);
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        assertThat(service.pause(job.getId()).isEnabled()).isFalse();
        assertThat(job.getConfigurationRevision()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"user,owner,foreign", "user,owner,", "admin,owner,foreign", "admin,,owner"})
    void attachedCreateRejectsForeignMissingOrUnattributedSessionBeforeSave(
            String role, String owner, String sessionOwner) {
        UserContext.set("owner", role);
        UUID id = UUID.randomUUID();
        when(sessions.findById(id)).thenReturn(sessionOwner == null ? Optional.empty() : Optional.of(session(id, sessionOwner)));
        assertThatThrownBy(() -> create(owner, id)).isInstanceOfAny(SecurityException.class, IllegalArgumentException.class);
        verify(jobs, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void attachedUpdateChecksPersistedJobOwnerBeforeChangingManagedEntity(String role) {
        UserContext.set("owner", role);
        var job = job("owner");
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        UUID id = UUID.randomUUID();
        when(sessions.findById(id)).thenReturn(Optional.of(session(id, "foreign")));

        assertThatThrownBy(() -> update(job, id)).isInstanceOf(SecurityException.class);
        assertThat(job.getName()).isEqualTo("original");
        assertThat(job.getAttachedSessionId()).isNull();
        verify(jobs, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void ownedSessionCanBeAttachedOnCreateAndUpdate(String role) {
        UserContext.set("owner", role);
        UUID id = UUID.randomUUID();
        when(sessions.findById(id)).thenReturn(Optional.of(session(id, "owner")));
        var created = create("owner", id);
        assertThat(created.getUserId()).isEqualTo("owner");
        assertThat(created.getAttachedSessionId()).isEqualTo(id);
        var existing = job("owner");
        when(jobs.findByIdForUpdate(existing.getId())).thenReturn(Optional.of(existing));
        assertThat(update(existing, id).getAttachedSessionId()).isEqualTo(id);
        assertThat(existing.getName()).isEqualTo("changed");
    }

    @Test
    void attachedOperationRequiresSessionRepositoryWhileUnattachedCreateRemainsAvailable() {
        UserContext.set("owner", UserContext.ROLE_USER);
        ReflectionTestUtils.setField(service, "sessionRepositoryProvider", null);
        assertThatThrownBy(() -> create("owner", UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("ownership");
        verify(jobs, never()).save(any());
        assertThat(create("owner", null).getUserId()).isEqualTo("owner");
    }

    private CronJobEntity create(String owner, UUID session) {
        return service.create(owner, "owned", "0 * * * *", "work", null, null, null,
            null, null, false, null, null, null, null, null, null, false, session);
    }

    private CronJobEntity update(CronJobEntity job, UUID session) {
        return service.update(job.getId(), "changed", null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, true, session);
    }

    private static CronJobEntity job(String owner) {
        var job = new CronJobEntity();
        job.setId(UUID.randomUUID());
        job.setUserId(owner);
        job.setName("original");
        job.setPrompt("work");
        job.setSchedule("0 * * * *");
        job.setEnabled(true);
        return job;
    }

    private static SessionEntity session(UUID id, String owner) {
        var session = new SessionEntity();
        session.setId(id);
        session.setUserId(owner);
        return session;
    }
}
