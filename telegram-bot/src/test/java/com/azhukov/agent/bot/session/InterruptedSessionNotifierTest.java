package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.client.TelegramClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-b: restart recovery — sessions still flagged resume_pending when the bot
 * comes back up get an honest notice; the flag is cleared regardless of
 * delivery success (no eternal re-notification).
 */
@ExtendWith(MockitoExtension.class)
class InterruptedSessionNotifierTest {

    @Mock
    private BotSessionRepository repository;

    @Mock
    private TelegramClient telegramClient;

    @Test
    void interruptedSessionGetsNoticeAndFlagCleared() {
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        session.setChatId("500");
        session.setThreadId(null);
        session.setResumePending(true);
        session.setActive(true);
        when(repository.findByResumePendingTrueAndActiveTrue()).thenReturn(List.of(session));
        when(telegramClient.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(1L));

        new InterruptedSessionNotifier(repository, telegramClient).notifyInterruptedSessions();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramClient).sendMessage(eq(500L), text.capture(), any(), any(), any(), anyBoolean());
        assertThat(text.getValue()).contains("перезапущен").contains("сессии сохранён");
        assertThat(session.isResumePending()).isFalse();
        verify(repository).save(session);
    }

    @Test
    void topicSessionNoticeRoutesIntoItsThread() {
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        session.setChatId("-200");
        session.setThreadId(7L);
        session.setResumePending(true);
        when(repository.findByResumePendingTrueAndActiveTrue()).thenReturn(List.of(session));
        when(telegramClient.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(1L));

        new InterruptedSessionNotifier(repository, telegramClient).notifyInterruptedSessions();

        verify(telegramClient).sendMessage(eq(-200L), anyString(), any(), any(), eq(7), anyBoolean());
    }

    @Test
    void undeliverableNoticeStillClearsTheFlag() {
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        session.setChatId("500");
        session.setResumePending(true);
        when(repository.findByResumePendingTrueAndActiveTrue()).thenReturn(List.of(session));
        when(telegramClient.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenThrow(new RuntimeException("telegram down"));

        new InterruptedSessionNotifier(repository, telegramClient).notifyInterruptedSessions();

        assertThat(session.isResumePending()).isFalse();
        verify(repository).save(session);
    }

    @Test
    void unparseableChatIdIsSkippedWithoutClearedFlag() {
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId("100");
        session.setChatId("not-a-number");
        session.setResumePending(true);
        when(repository.findByResumePendingTrueAndActiveTrue()).thenReturn(List.of(session));

        new InterruptedSessionNotifier(repository, telegramClient).notifyInterruptedSessions();

        verify(telegramClient, never()).sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean());
        // flagged row kept — it stays visible to future recovery sweeps
        assertThat(session.isResumePending()).isTrue();
    }

    @Test
    void noFlaggedSessionsMeansNoTraffic() {
        when(repository.findByResumePendingTrueAndActiveTrue()).thenReturn(List.of());

        new InterruptedSessionNotifier(repository, telegramClient).notifyInterruptedSessions();

        verify(telegramClient, never()).sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean());
        verify(repository, never()).save(any(BotSessionEntity.class));
    }

    @Test
    void repositoryFailureIsSurvived() {
        when(repository.findByResumePendingTrueAndActiveTrue())
            .thenThrow(new RuntimeException("db down"));

        new InterruptedSessionNotifier(repository, telegramClient).notifyInterruptedSessions();

        verify(telegramClient, never()).sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean());
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
