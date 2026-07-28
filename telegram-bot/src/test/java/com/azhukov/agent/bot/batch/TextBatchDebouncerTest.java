package com.azhukov.agent.bot.batch;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TextBatchDebouncerTest {

    private BotProperties properties;
    private TextBatchDebouncer debouncer;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        // Use very short delays for testing
        properties.getTextBatch().setFastDelayMs(50);
        properties.getTextBatch().setDelayMs(100);
        properties.getTextBatch().setSplitDelayMs(200);
        debouncer = new TextBatchDebouncer(properties);
    }

    @AfterEach
    void tearDown() {
        debouncer.flushAll();
    }

    @Test
    void offer_singleMessage_dispatchesAfterDelay() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> dispatched = new AtomicReference<>("");

        debouncer.onDispatch(event -> {
            dispatched.set(event.text());
            latch.countDown();
        });

        UpdateEvent event = makeTextEvent(1L, 100L, "Hello world");
        boolean buffered = debouncer.offer(event);

        assertThat(buffered).isTrue();
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatched.get()).isEqualTo("Hello world");
    }

    @Test
    void offer_multipleMessages_mergesWithNewline() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> dispatched = new AtomicReference<>("");

        debouncer.onDispatch(event -> {
            dispatched.set(event.text());
            latch.countDown();
        });

        debouncer.offer(makeTextEvent(1L, 100L, "Hello"));
        Thread.sleep(10); // Small gap — still within debounce window
        debouncer.offer(makeTextEvent(2L, 100L, "world"));
        Thread.sleep(10);
        debouncer.offer(makeTextEvent(3L, 100L, "!"));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatched.get()).isEqualTo("Hello\nworld\n!");
    }

    @Test
    void offer_nonTextEvent_returnsFalse() {
        UpdateEvent photoEvent = new UpdateEvent(1L, UpdateEvent.Type.PHOTO, 100L, 200L,
            "jdoe", null, "caption", "fileId", "photo",
            null, null, null, false, null, null, 0L, null);

        boolean buffered = debouncer.offer(photoEvent);
        assertThat(buffered).isFalse();
    }

    @Test
    void computeDelay_shortMessage_usesFastDelay() {
        properties.getTextBatch().setFastDelayMs(180);
        properties.getTextBatch().setDelayMs(500);
        properties.getTextBatch().setSplitDelayMs(1200);

        assertThat(debouncer.computeDelay(100)).isEqualTo(180);   // ≤320 → fast
        assertThat(debouncer.computeDelay(320)).isEqualTo(180);   // boundary
    }

    @Test
    void computeDelay_longMessage_usesSplitDelay() {
        properties.getTextBatch().setFastDelayMs(180);
        properties.getTextBatch().setDelayMs(500);
        properties.getTextBatch().setSplitDelayMs(1200);

        assertThat(debouncer.computeDelay(500)).isEqualTo(500);   // ≤1024 → medium
        assertThat(debouncer.computeDelay(1024)).isEqualTo(500);  // boundary
        assertThat(debouncer.computeDelay(2000)).isEqualTo(1200);  // >1024 → split
    }

    private UpdateEvent makeTextEvent(long updateId, long chatId, String text) {
        return new UpdateEvent(updateId, UpdateEvent.Type.TEXT, chatId, 200L,
            "jdoe", text, null, null, null,
            null, null, null, false, null, null, 0L, null);
    }
}