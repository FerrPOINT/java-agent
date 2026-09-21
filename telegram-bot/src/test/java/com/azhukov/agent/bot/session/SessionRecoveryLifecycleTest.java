package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.client.TelegramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRecoveryLifecycleTest {

    private SessionRecoveryService recoveryService;
    private TelegramClient telegramClient;
    private SessionRecoveryLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        recoveryService = mock(SessionRecoveryService.class);
        telegramClient = mock(TelegramClient.class);
        lifecycle = new SessionRecoveryLifecycle(recoveryService, telegramClient);
    }

    @Test
    void acknowledgesPendingSessionOnlyAfterNoticeIsDelivered() {
        UUID botSessionId = UUID.randomUUID();
        when(recoveryService.recoverPendingSessions()).thenReturn(List.of(
            new SessionRecoveryService.RecoveredSession("100", 0L, UUID.randomUUID(), botSessionId)));
        when(telegramClient.sendMessage(anyLong(), anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false)))
            .thenReturn(Optional.of(1L));

        lifecycle.run(new DefaultApplicationArguments());

        verify(recoveryService).acknowledgeRecoveredSession(botSessionId);
    }

    @Test
    void preservesPendingSessionWhenTelegramDoesNotDeliverNotice() {
        UUID botSessionId = UUID.randomUUID();
        when(recoveryService.recoverPendingSessions()).thenReturn(List.of(
            new SessionRecoveryService.RecoveredSession("100", 0L, UUID.randomUUID(), botSessionId)));
        when(telegramClient.sendMessage(anyLong(), anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false)))
            .thenReturn(Optional.empty());

        lifecycle.run(new DefaultApplicationArguments());

        verify(recoveryService, never()).acknowledgeRecoveredSession(botSessionId);
    }
}
