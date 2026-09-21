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
 * - startup leaves each interrupted turn pending until the lifecycle confirms
 *   successful notification delivery;
 * - a delivery acknowledgement clears exactly the matching pending session;
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
    void recoveringPendingSessionDoesNotClearFlagBeforeDelivery() {
        BotSessionEntity pending = session("u1", true, false);
        pending.setResumePending(true);
        when(repository.findByResumePendingTrueAndActiveTrue())
                .thenReturn(List.of(pending));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var recovered = recovery.recoverPendingSessions();

        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).chatId()).isEqualTo("100");
        assertThat(pending.isResumePending()).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void startupWithNoPendingSessionsIsNoop() {
        when(repository.findByResumePendingTrueAndActiveTrue()).thenReturn(List.of());

        var recovered = recovery.recoverPendingSessions();

        assertThat(recovered).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void acknowledgesOnlyKnownPendingSessionAfterDelivery() {
        UUID sessionId = UUID.randomUUID();
        when(store.clearResumePending(sessionId)).thenReturn(true);

        boolean cleared = recovery.acknowledgeRecoveredSession(sessionId);

        assertThat(cleared).isTrue();
        verify(store).clearResumePending(sessionId);
    }

    @Test
    void doesNotAcknowledgeMissingSessionId() {
        assertThat(recovery.acknowledgeRecoveredSession(null)).isFalse();
        verifyNoInteractions(store);
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
