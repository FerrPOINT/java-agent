package com.azhukov.agent.bot.polling;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.client.TelegramClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** The real start/stop lifecycle acquires a filesystem/PID gateway lock. */
@Tag("integration")
class LongPollingServiceConflictLifecycleTest {

    @Test
    void pollingLifecycleUsesClientWithGatewayLock() throws Exception {
        TelegramClient telegramClient = mock(TelegramClient.class);
        BotProperties properties = new BotProperties();
        properties.setToken("test-token-conflict-" + System.nanoTime());
        properties.setReplaceOnStart(true);
        properties.getPolling().setTimeoutSeconds(0);
        properties.getPolling().setLimit(10);
        properties.getPolling().setConflictMaxRetries(3);
        ReconnectWatcher reconnectWatcher = new ReconnectWatcher();
        LongPollingService service = new LongPollingService(
            telegramClient, properties, event -> {}, reconnectWatcher);
        CountDownLatch getUpdatesCalled = new CountDownLatch(1);
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                getUpdatesCalled.countDown();
                return Optional.empty();
            });
        try {
            service.start();
            assertThat(getUpdatesCalled.await(5, TimeUnit.SECONDS)).isTrue();
            verify(telegramClient, atLeast(1)).getUpdates(anyLong(), anyInt(), anyInt());
        } finally {
            service.stop();
            reconnectWatcher.stop();
        }
    }
}
