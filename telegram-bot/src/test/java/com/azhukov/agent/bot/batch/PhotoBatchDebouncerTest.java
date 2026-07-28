package com.azhukov.agent.bot.batch;

import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoBatchDebouncerTest {

    private PhotoBatchDebouncer debouncer;

    @BeforeEach
    void setUp() {
        debouncer = new PhotoBatchDebouncer();
    }

    @AfterEach
    void tearDown() {
        debouncer.flushAll();
    }

    @Test
    void offer_singlePhoto_noMediaGroupId_returnsFalse() {
        UpdateEvent photo = makePhotoEvent(1L, 100L, "file1", null, "caption1");
        boolean buffered = debouncer.offer(photo);
        assertThat(buffered).isFalse();
    }

    @Test
    void offer_photoWithMediaGroupId_returnsTrue() {
        UpdateEvent photo = makePhotoEvent(1L, 100L, "file1", "group1", "caption1");
        boolean buffered = debouncer.offer(photo);
        assertThat(buffered).isTrue();
        assertThat(debouncer.hasPending("group1")).isTrue();
    }

    @Test
    void offer_multiplePhotosSameGroup_dispatchesMerged() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger(0);
        AtomicReference<UpdateEvent> dispatched = new AtomicReference<>();

        debouncer.onDispatch(event -> {
            count.set(1);
            dispatched.set(event);
            latch.countDown();
        });

        debouncer.offer(makePhotoEvent(1L, 100L, "file1", "group1", "caption1"));
        Thread.sleep(50);
        debouncer.offer(makePhotoEvent(2L, 100L, "file2", "group1", "caption2"));
        Thread.sleep(50);
        debouncer.offer(makePhotoEvent(3L, 100L, "file3", "group1", null));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        UpdateEvent merged = dispatched.get();
        assertThat(merged).isNotNull();
        // Merged event contains all file IDs joined by comma
        assertThat(merged.fileId()).contains("file1");
        assertThat(merged.fileId()).contains("file2");
        assertThat(merged.fileId()).contains("file3");
        assertThat(merged.caption()).isEqualTo("caption1\ncaption2"); // merged captions
    }

    @Test
    void offer_photosDifferentGroups_dispatchesSeparately() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger dispatchCount = new AtomicInteger(0);

        debouncer.onDispatch(event -> {
            dispatchCount.incrementAndGet();
            latch.countDown();
        });

        debouncer.offer(makePhotoEvent(1L, 100L, "file1", "groupA", "cap1"));
        debouncer.offer(makePhotoEvent(2L, 100L, "file2", "groupB", "cap2"));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatchCount.get()).isEqualTo(2);
    }

    private UpdateEvent makePhotoEvent(long updateId, long chatId, String fileId,
                                        String mediaGroupId, String caption) {
        return new UpdateEvent(updateId, UpdateEvent.Type.PHOTO, chatId, 200L,
            "jdoe", null, caption, fileId, "photo",
            null, null, null, false, null, null, 0L, mediaGroupId);
    }
}