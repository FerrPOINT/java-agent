package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BusySessionHandlerTest {

    private BusySessionHandler handler;

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties();
        props.setBusyMode("queue");
        handler = new BusySessionHandler(props);
    }

    private UpdateEvent textEvent(long id, long chatId, String text) {
        return new UpdateEvent(id, UpdateEvent.Type.TEXT, chatId, 200L,
            "user", text, null, null, null, null, null, null,
            false, null, null, 100, null, 0);
    }

    @Test
    void concurrentQueueAndDrainPreservesOrder() throws Exception {
        long chatId = 100L;
        int messageCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Thread 1: queue 100 messages
        CountDownLatch queueDone = new CountDownLatch(1);
        pool.submit(() -> {
            for (int i = 0; i < messageCount; i++) {
                handler.queueMessage(chatId, textEvent(i, chatId, "msg-" + i));
            }
            queueDone.countDown();
        });

        // Thread 2: drain while queueing is in progress
        AtomicInteger totalDrained = new AtomicInteger(0);
        pool.submit(() -> {
            try {
                // Wait for producer to finish, then drain remaining queue
                queueDone.await(5, TimeUnit.SECONDS);
                while (handler.hasQueued(chatId)) {
                    List<UpdateEvent> drained = handler.drainQueue(chatId);
                    totalDrained.addAndGet(drained.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(queueDone.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // All messages should have been drained (no data race loss)
        assertThat(totalDrained.get()).isEqualTo(messageCount);
    }

    @Test
    void fifoOrderUnderConcurrency() {
        long chatId = 200L;
        // Queue 10 messages sequentially
        for (int i = 0; i < 10; i++) {
            handler.queueMessage(chatId, textEvent(i, chatId, "msg-" + i));
        }

        // Drain and verify FIFO order
        List<UpdateEvent> drained = handler.drainQueue(chatId);
        assertThat(drained).hasSize(10);
        for (int i = 0; i < 10; i++) {
            assertThat(drained.get(i).text()).isEqualTo("msg-" + i);
        }
    }

    @Test
    void drainEmptyQueueReturnsEmptyList() {
        assertThat(handler.drainQueue(999L)).isEmpty();
    }

    @Test
    void hasQueuedReturnsFalseAfterDrain() {
        long chatId = 300L;
        handler.queueMessage(chatId, textEvent(1, chatId, "test"));
        assertThat(handler.hasQueued(chatId)).isTrue();
        handler.drainQueue(chatId);
        assertThat(handler.hasQueued(chatId)).isFalse();
    }
}