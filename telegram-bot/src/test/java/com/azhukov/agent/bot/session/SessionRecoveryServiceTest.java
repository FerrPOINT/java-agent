package com.azhukov.agent.bot.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Active-session recovery (docs/34 gap 9 remainder): the bot must survive
 * restarts without losing the user's session thread.
 *
 * Contract under test:
 * - graceful shutdown marks every active, non-suspended session
 *   resume_pending so the next boot knows an in-flight turn was interrupted;
 * - startup recovery finds those sessions, notifies each chat once and
 *   clears the flag so the notice cannot repeat on every boot;
 * - suspended sessions are never touched (explicit user intent wins);
 * - the first successful post-restart turn also clears the flag.
 */
class SessionRecoveryServiceTest {

    private BotSessionRepository repository;
    private BotSessionStore store;
    private SessionRecoveryService recovery;

    @BeforeEach
    void setUp() {
        repository = mock(BotSessionRepository.class);
        store = mock(BotSessionStore.class);
        recovery = new SessionRecoveryService(repository, store);
    }

    @Test
    void shutdownMarksActiveSessionsResumePending() {
        BotSessionEntity active = session("u1", true, false);
        BotSessionEntity suspended = session("u2", true, true);
        BotSessionEntity inactive = session("u3", false, false);
        when(repository.findByActiveTrueAndSuspendedFalse()).thenReturn(List.of(active));

        int marked = recovery.markInterruptedOnShutdown();

        assertThat(marked).isEqualTo(1);
        assertThat(active.isResumePending()).isTrue();
        verify(repository).save(active);
        verify(repository, never()).save(suspended);
        verify(repository, never()).save(inactive);
    }

    @Test
    void shutdownSkipsAlreadyPendingSessions() {
        BotSessionEntity pending = session("u1", true, false);
        pending.setResumePending(true);
        when(repository.findByActiveTrueAndSuspendedFalse()).thenReturn(List.of(pending));

        int marked = recovery.markInterruptedOnShutdown();

        assertThat(marked).isZero();
        verify(repository, never()).save(any(BotSessionEntity.class));
    }

    @Test
    void startupNotifiesPendingSessionsAndClearsFlag() {
        BotSessionEntity pending = session("u1", true, false);
        pending.setResumePending(true);
        when(repository.findByResumePendingTrueAndActiveTrue())
                .thenReturn(List.of(pending));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var recovered = recovery.recoverPendingSessions();

        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).chatId()).isEqualTo("100");
        assertThat(pending.isResumePending()).isFalse();
        verify(repository).save(pending);
    }

    @Test
    void startupWithNoPendingSessionsIsNoop() {
        when(repository.findByResumePendingTrueAndActiveTrue()).thenReturn(List.of());

        var recovered = recovery.recoverPendingSessions();

        assertThat(recovered).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void resumeNoticeMentionsContinuation() {
        BotSessionEntity pending = session("u1", true, false);
        pending.setResumePending(true);

        String notice = SessionRecoveryService.recoveryNotice();

        assertThat(notice).containsIgnoringCase("restart");
        assertThat(notice).containsIgnoringCase("continue");
    }

    private BotSessionEntity session(String userId, boolean active, boolean suspended) {
        BotSessionEntity entity = new BotSessionEntity();
        entity.setUserId(userId);
        entity.setChatId("100");
        entity.setActive(active);
        entity.setSuspended(suspended);
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
