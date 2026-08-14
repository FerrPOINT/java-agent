package com.azhukov.agent.bot.typing;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for P1-9: Typing indicator pause/resume at approval gates.
 * <p>
 * When an approval gate is active, typing should be paused.
 * After approval is granted/denied, typing should resume.
 */
class TypingManagerPauseResumeTest {

    private TelegramClient client;
    private TypingManager manager;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        BotProperties props = new BotProperties();
        props.setTypingRefreshInterval(Duration.ofMillis(50));
        manager = new TypingManager(client, props);
        manager.init();
    }

    @Test
    void pauseTyping_cancelsRefreshTask() throws InterruptedException {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        Thread.sleep(80); // Let a few refreshes happen

        int countBeforePause = mockingDetails(client).getInvocations().size();
        manager.pauseTyping(123L);
        Thread.sleep(150);

        int countAfterPause = mockingDetails(client).getInvocations().size();
        // No new sendTyping calls should happen after pause
        assertThat(countAfterPause).isEqualTo(countBeforePause);
    }

    @Test
    void isPaused_trueAfterPause() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        manager.pauseTyping(123L);
        assertThat(manager.isPaused(123L)).isTrue();
    }

    @Test
    void isPaused_falseBeforePause() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        assertThat(manager.isPaused(123L)).isFalse();
    }

    @Test
    void isPaused_falseAfterResume() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        manager.pauseTyping(123L);
        manager.resumeTyping(123L);
        assertThat(manager.isPaused(123L)).isFalse();
    }

    @Test
    void resumeTyping_restartsPeriodicRefresh() throws InterruptedException {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        Thread.sleep(80);
        manager.pauseTyping(123L);
        Thread.sleep(80);
        clearInvocations(client);

        manager.resumeTyping(123L);
        // Immediate send on resume
        verify(client, atLeast(1)).sendTyping(eq(123L), any());
        Thread.sleep(150);
        // Should have continued periodic refreshes
        verify(client, atLeast(2)).sendTyping(eq(123L), any());
    }

    @Test
    void resumeTyping_withoutPause_isNoOp() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        clearInvocations(client);

        manager.resumeTyping(123L);
        // Should not send any typing indicator since we weren't paused
        verifyNoInteractions(client);
    }

    @Test
    void stopTyping_clearsPausedState() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        manager.pauseTyping(123L);
        assertThat(manager.isPaused(123L)).isTrue();

        manager.stopTyping(123L);
        assertThat(manager.isPaused(123L)).isFalse();
        assertThat(manager.isTyping(123L)).isFalse();
    }

    @Test
    void pauseResume_withThreadRouting_preservesThreadId() throws InterruptedException {
        when(client.sendTyping(anyLong(), eq(42))).thenReturn(true);
        manager.startTyping(123L, 42);
        Thread.sleep(80);
        manager.pauseTyping(123L);
        clearInvocations(client);

        manager.resumeTyping(123L);
        // The resumed typing should use the same thread id
        verify(client).sendTyping(eq(123L), eq(42));
    }

    @Test
    void pauseTyping_whenNotActive_isNoOp() {
        // Pause without prior startTyping should not throw or cause issues
        manager.pauseTyping(999L);
        assertThat(manager.isPaused(999L)).isFalse();
    }
}