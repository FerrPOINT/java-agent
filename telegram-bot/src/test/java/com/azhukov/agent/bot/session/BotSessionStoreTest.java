package com.azhukov.agent.bot.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BotSessionStoreTest {

    private BotSessionRepository repository;
    private BotSessionStore store;

    @BeforeEach
    void setUp() {
        repository = mock(BotSessionRepository.class);
        store = new BotSessionStore(repository);
    }

    @Test
    void switchingNamedSessionKeepsOtherForumTopicsActive() {
        var target = new BotSessionEntity(); target.setId(UUID.randomUUID()); target.setUserId("owner");
        target.setActive(false); target.setCleanupPending(true);
        var previous = new BotSessionEntity(); previous.setId(UUID.randomUUID()); previous.setUserId("owner"); previous.setActive(true);
        var other = new BotSessionEntity(); other.setId(UUID.randomUUID()); other.setUserId("owner"); other.setThreadId(42L); other.setActive(true);
        when(repository.findAllLockedByUserId("owner")).thenReturn(List.of(target, previous, other));

        assertThat(store.activateSessionById("owner", target.getId())).isTrue();
        assertThat(previous.isActive()).isFalse();
        assertThat(other.isActive()).isTrue();
        assertThat(target.isActive()).isTrue();
        assertThat(target.isCleanupPending()).isFalse();
        verify(repository, never()).deactivateAllForUser(any());
        var order = inOrder(repository);
        order.verify(repository).saveAndFlush(previous);
        order.verify(repository).save(target);
    }

    @Test
    void switchingAlreadyActiveNamedSessionKeepsItActive() {
        var current = new BotSessionEntity(); current.setId(UUID.randomUUID()); current.setUserId("owner"); current.setActive(true);
        when(repository.findAllLockedByUserId("owner")).thenReturn(List.of(current));
        assertThat(store.activateSessionById("owner", current.getId())).isTrue();
        assertThat(current.isActive()).isTrue();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void resumeSwitchesOnlyTheTargetTopicAndCancelsItsPendingCleanup() {
        var target = new BotSessionEntity();
        target.setId(UUID.randomUUID()); target.setUserId("owner"); target.setThreadId(42L);
        target.setActive(false); target.setCleanupPending(true); target.setCleanupOnBackendBinding(true);
        var previous = new BotSessionEntity();
        previous.setId(UUID.randomUUID()); previous.setUserId("owner"); previous.setThreadId(42L); previous.setActive(true);
        var other = new BotSessionEntity();
        other.setId(UUID.randomUUID()); other.setUserId("owner"); other.setThreadId(99L); other.setActive(true);
        when(repository.findAllLockedByUserId("owner")).thenReturn(List.of(target, previous, other));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(store.resumeSession(target.getId(), "owner")).isSameAs(target);
        assertThat(target.isActive()).isTrue();
        assertThat(target.isCleanupPending()).isFalse();
        assertThat(target.isCleanupOnBackendBinding()).isFalse();
        assertThat(previous.isActive()).isFalse();
        assertThat(other.isActive()).isTrue();
        var order = inOrder(repository);
        order.verify(repository).saveAndFlush(previous);
        order.verify(repository).save(target);
    }

    @Test
    void pendingCleanupRetriesFailureAndClearsOnlyAfterSuccess() {
        UUID id = UUID.randomUUID();
        UUID backend = UUID.randomUUID();
        var row = new BotSessionEntity();
        row.setId(id); row.setBackendSessionId(backend); row.setActive(false); row.setCleanupPending(true);
        when(repository.findLockedById(id)).thenReturn(Optional.of(row));
        assertThat(store.retryCleanup(id, ignored -> false)).isFalse();
        assertThat(row.isCleanupPending()).isTrue();
        assertThat(store.retryCleanup(id, ignored -> true)).isTrue();
        assertThat(row.isCleanupPending()).isFalse();
    }

    @Test
    void pendingCleanupDoesNotTouchActiveSession() {
        UUID id = UUID.randomUUID();
        var row = new BotSessionEntity();
        row.setId(id); row.setActive(true); row.setCleanupPending(true);
        when(repository.findLockedById(id)).thenReturn(Optional.of(row));
        java.util.function.Function<UUID, Boolean> action = mock(java.util.function.Function.class);
        assertThat(store.retryCleanup(id, action)).isFalse();
        verifyNoInteractions(action);
        assertThat(row.isCleanupPending()).isTrue();
    }

    @Test
    void resolveOrCreate_existingSession_returnsExisting() {
        BotSessionEntity existing = new BotSessionEntity();
        existing.setUserId("123");
        existing.setActive(true);
        when(repository.findByUserIdAndActiveTrue("123")).thenReturn(Optional.of(existing));
        org.mockito.Mockito.lenient().when(repository.findByUserIdAndThreadIdAndActiveTrue("123", null)).thenReturn(Optional.of(existing));

        BotSessionEntity result = store.resolveOrCreate("123", "456", "user");
        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void resolveOrCreate_noExistingSession_createsNew() {
        when(repository.findByUserIdAndActiveTrue("123")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BotSessionEntity result = store.resolveOrCreate("123", "456", "user");
        assertThat(result.getUserId()).isEqualTo("123");
        assertThat(result.getChatId()).isEqualTo("456");
        assertThat(result.getUsername()).isEqualTo("user");
        assertThat(result.isActive()).isTrue();
        assertThat(result.getCreatedAt()).isNotNull();
        verify(repository).save(any());
    }

    @Test
    void updateTitle_savesNewTitle() {
        UUID id = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(session));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.updateTitle(id, "New Title");
        assertThat(session.getTitle()).isEqualTo("New Title");
        verify(repository).save(session);
    }

    @Test
    void updateTitle_nonExistentSession_doesNothing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        store.updateTitle(id, "Title");
        verify(repository, never()).save(any());
    }

    @Test
    void toggleYolo_flipsAndSaves() {
        UUID id = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        session.setYoloMode(false);
        when(repository.findById(id)).thenReturn(Optional.of(session));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = store.toggleYolo(id);
        assertThat(result).isTrue();
        assertThat(session.isYoloMode()).isTrue();
        verify(repository).save(session);
    }

    @Test
    void toggleVerbose_flipsAndSaves() {
        UUID id = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        session.setVerboseMode(false);
        when(repository.findById(id)).thenReturn(Optional.of(session));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = store.toggleVerbose(id);
        assertThat(result).isTrue();
        assertThat(session.isVerboseMode()).isTrue();
    }

    @Test
    void toggleFast_flipsAndSaves() {
        UUID id = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        session.setFastMode(false);
        when(repository.findById(id)).thenReturn(Optional.of(session));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = store.toggleFast(id);
        assertThat(result).isTrue();
        assertThat(session.isFastMode()).isTrue();
    }

    @Test
    void setReasoningLevel_saves() {
        UUID id = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(session));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.setReasoningLevel(id, "high");
        assertThat(session.getReasoningLevel()).isEqualTo("high");
        verify(repository).save(session);
    }

    @Test
    void setModelOverride_saves() {
        UUID id = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(session));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.setModelOverride(id, "gpt-4o");
        assertThat(session.getModelOverride()).isEqualTo("gpt-4o");
        verify(repository).save(session);
    }

    @Test
    void listByUserId_delegatesToRepository() {
        BotSessionEntity s1 = new BotSessionEntity();
        s1.setUserId("123");
        s1.setUpdatedAt(Instant.now());
        when(repository.findByUserIdOrderByUpdatedAtDesc("123")).thenReturn(List.of(s1));

        List<BotSessionEntity> result = store.listByUserId("123");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo("123");
    }

    @Test
    void deactivateAll_delegatesToRepository() {
        when(repository.deactivateAllForUser("123")).thenReturn(3);
        int count = store.deactivateAll("123");
        assertThat(count).isEqualTo(3);
        verify(repository).deactivateAllForUser("123");
    }

    @Test
    void toggleYolo_nonExistent_returnsFalse() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        boolean result = store.toggleYolo(id);
        assertThat(result).isFalse();
        verify(repository, never()).save(any());
    }
}