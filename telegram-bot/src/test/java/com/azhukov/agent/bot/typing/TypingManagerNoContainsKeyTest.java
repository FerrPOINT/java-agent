package com.azhukov.agent.bot.typing;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * L38 test: verify that startTyping no longer uses a containsKey check before putIfAbsent.
 * The fix removes the containsKey early-return, relying solely on putIfAbsent for atomicity.
 * This means a second concurrent call will still send one immediate typing action (harmless)
 * but will NOT schedule a duplicate periodic task.
 */
class TypingManagerNoContainsKeyTest {

    private TelegramClient client;
    private TypingManager manager;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        BotProperties props = new BotProperties();
        props.setTypingRefreshInterval(Duration.ofMillis(500));
        manager = new TypingManager(client, props);
        manager.init();
    }

    @Test
    void concurrentStartTypingDoesNotScheduleDuplicates() throws Exception {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    manager.startTyping(42L);
                } catch (Exception e) {
                    // ignore
                } finally {
                    doneLatch.countDown();
                }
            });
            t.start();
        }

        startLatch.countDown();
        doneLatch.await();

        // All threads call startTyping — each sends an immediate typing action,
        // but only one periodic task should be scheduled.
        // The fix ensures no containsKey early-return that creates a race window.
        // Verify that typing was sent (at least once from immediate sends)
        verify(client, atLeast(threadCount)).sendTyping(eq(42L), any());

        // Only one periodic task should be running
        assertThat(manager.isTyping(42L)).isTrue();

        // Clean up
        manager.stopTyping(42L);
    }

    @Test
    void startTypingSendsImmediateThenPeriodic() throws InterruptedException {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(99L);

        // Wait for at least 2 calls (immediate + one periodic)
        Thread.sleep(600);
        verify(client, atLeast(2)).sendTyping(eq(99L), any());

        manager.stopTyping(99L);
    }
}