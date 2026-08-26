package com.azhukov.agent.bot.typing;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        // Use latch to wait for at least 2 sendTyping calls before pausing
        CountDownLatch typingLatch = new CountDownLatch(2);
        when(client.sendTyping(anyLong(), any())).thenAnswer(inv -> {
            typingLatch.countDown();
            return true;
        });
        manager.startTyping(123L);
        assertThat(typingLatch.await(2, TimeUnit.SECONDS)).isTrue();

        int countBeforePause = mockingDetails(client).getInvocations().size();
        manager.pauseTyping(123L);
        // Actual timing: verify no periodic task fires after pause
        Thread.sleep(150); // timing-assertion

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
        // Use latch to wait for initial typing calls before pause
        CountDownLatch initialLatch = new CountDownLatch(2);
        when(client.sendTyping(anyLong(), any())).thenAnswer(inv -> {
            initialLatch.countDown();
            return true;
        });
        manager.startTyping(123L);
        assertThat(initialLatch.await(2, TimeUnit.SECONDS)).isTrue();
        manager.pauseTyping(123L);
        // Actual timing: verify pause takes effect before resume
        Thread.sleep(100); // timing-assertion
        clearInvocations(client);

        // After resume, use a latch to wait for at least 2 more calls
        CountDownLatch resumeLatch = new CountDownLatch(2);
        when(client.sendTyping(anyLong(), any())).thenAnswer(inv -> {
            resumeLatch.countDown();
            return true;
        });
        manager.resumeTyping(123L);
        // Immediate send on resume + periodic refreshes
        assertThat(resumeLatch.await(2, TimeUnit.SECONDS)).isTrue();
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
        // Use latch to wait for initial typing before pause
        CountDownLatch initialLatch = new CountDownLatch(1);
        when(client.sendTyping(anyLong(), eq(42))).thenAnswer(inv -> {
            initialLatch.countDown();
            return true;
        });
        manager.startTyping(123L, 42);
        assertThat(initialLatch.await(2, TimeUnit.SECONDS)).isTrue();
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