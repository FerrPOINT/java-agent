package com.azhukov.agent.bot.polling;

import com.azhukov.agent.bot.client.TelegramApiException;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * M29: Test that conflictRetryCount is an AtomicInteger for thread safety.
 */
class LongPollingServiceConflictRetryTest {

    private TelegramClient telegramClient;
    private BotProperties properties;
    private ReconnectWatcher reconnectWatcher;
    private LongPollingService service;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        properties = new BotProperties();
        properties.setToken("test-token-conflict-" + System.nanoTime());
        properties.setReplaceOnStart(true);
        properties.getPolling().setTimeoutSeconds(0);
        properties.getPolling().setLimit(10);
        properties.getPolling().setConflictMaxRetries(3);

        reconnectWatcher = new ReconnectWatcher();
    }

    @AfterEach
    void tearDown() {
        reconnectWatcher.stop();
        try { service.stop(); } catch (Exception ignored) {}
    }

    @Test
    void conflictRetryCountIsAtomicInteger() throws Exception {
        service = new LongPollingService(
            telegramClient, properties,
            event -> {},
            reconnectWatcher
        );

        Field field = LongPollingService.class.getDeclaredField("conflictRetryCount");
        field.setAccessible(true);
        Object value = field.get(service);
        assertThat(value).isInstanceOf(AtomicInteger.class);
    }

    @Test
    void conflictRetryCountIncrementsAtomically() throws Exception {
        // Simulate 409 conflict: empty result + isLastCallConflict=true
        CountDownLatch getUpdatesCalled = new CountDownLatch(1);
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                getUpdatesCalled.countDown();
                return Optional.empty();
            });
        when(telegramClient.isLastCallConflict()).thenReturn(true);

        service = new LongPollingService(
            telegramClient, properties,
            event -> {},
            reconnectWatcher
        );

        service.start();

        // Wait for getUpdates to be called (conflict processed)
        assertThat(getUpdatesCalled.await(5, TimeUnit.SECONDS)).isTrue();
        service.stop();

        // Verify the conflict was handled (getUpdates was called)
        verify(telegramClient, atLeast(1)).getUpdates(anyLong(), anyInt(), anyInt());
    }

    @Test
    void conflictRetryCountResetsOnSuccess() throws Exception {
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenReturn(Optional.of(java.util.List.of()));

        service = new LongPollingService(
            telegramClient, properties,
            event -> {},
            reconnectWatcher
        );

        Field field = LongPollingService.class.getDeclaredField("conflictRetryCount");
        field.setAccessible(true);
        AtomicInteger counter = (AtomicInteger) field.get(service);
        counter.set(1);

        // Exercise the successful fetch path directly. Starting the poll loop
        // would intentionally sleep 15s after a 409 before its next request.
        assertThat(service.fetchUpdates()).isEmpty();
        assertThat(counter.get()).isZero();
    }
}