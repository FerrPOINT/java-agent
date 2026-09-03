package com.azhukov.agent.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowRateLimiterTest {

    private SlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new SlidingWindowRateLimiter();
    }

    @Test
    void allowsFirstCall() {
        boolean result = limiter.tryAcquire("key1", 5, 60);
        assertThat(result).isTrue();
    }

    @Test
    void allowsCallsUnderLimit() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("key1", 5, 60)).isTrue();
        }
    }

    @Test
    void blocksCallsOverLimit() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("key1", 3, 60)).isTrue();
        }
        assertThat(limiter.tryAcquire("key1", 3, 60)).isFalse();
    }

    @Test
    void differentKeysHaveIndependentLimits() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("key1", 3, 60)).isTrue();
        }
        // key2 should still be allowed
        assertThat(limiter.tryAcquire("key2", 3, 60)).isTrue();
        // key1 should be blocked
        assertThat(limiter.tryAcquire("key1", 3, 60)).isFalse();
    }

    @Test
    void zeroMaxCallsAlwaysAllowed() {
        assertThat(limiter.tryAcquire("key1", 0, 60)).isTrue();
    }

    @Test
    void zeroWindowSecondsAlwaysAllowed() {
        assertThat(limiter.tryAcquire("key1", 5, 0)).isTrue();
    }

    @Test
    void nullKeyAlwaysAllowed() {
        assertThat(limiter.tryAcquire(null, 5, 60)).isTrue();
    }

    @Test
    void blankKeyAlwaysAllowed() {
        assertThat(limiter.tryAcquire("", 5, 60)).isTrue();
    }

    @Test
    void windowExpiresAfterTimeout() throws InterruptedException {
        for (int i = 0; i < 2; i++) {
            assertThat(limiter.tryAcquire("key1", 2, 1)).isTrue();
        }
        assertThat(limiter.tryAcquire("key1", 2, 1)).isFalse();

        // Wait for window to expire (1 second + buffer)
        // timing-assertion: verifies window expiry after timeout duration
        Thread.sleep(1100);

        assertThat(limiter.tryAcquire("key1", 2, 1)).isTrue();
    }

    @Test
    void getCurrentCountReturnsZeroForUnknownKey() {
        assertThat(limiter.getCurrentCount("unknown", 60)).isZero();
    }

    @Test
    void getCurrentCountReturnsCountForKnownKey() {
        limiter.tryAcquire("key1", 10, 60);
        limiter.tryAcquire("key1", 10, 60);
        assertThat(limiter.getCurrentCount("key1", 60)).isEqualTo(2);
    }

    @Test
    void resetClearsSpecificKey() {
        limiter.tryAcquire("key1", 10, 60);
        limiter.tryAcquire("key2", 10, 60);
        limiter.reset("key1");
        assertThat(limiter.getCurrentCount("key1", 60)).isZero();
        assertThat(limiter.getCurrentCount("key2", 60)).isEqualTo(1);
    }

    @Test
    void clearRemovesAllKeys() {
        limiter.tryAcquire("key1", 10, 60);
        limiter.tryAcquire("key2", 10, 60);
        limiter.clear();
        assertThat(limiter.getCurrentCount("key1", 60)).isZero();
        assertThat(limiter.getCurrentCount("key2", 60)).isZero();
    }

    @Test
    void concurrentAccessIsThreadSafe() throws InterruptedException {
        int threads = 10;
        int callsPerThread = 10;
        int maxCalls = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger blocked = new AtomicInteger(0);

        try {
            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int i = 0; i < callsPerThread; i++) {
                            if (limiter.tryAcquire("shared", maxCalls, 60)) {
                                allowed.incrementAndGet();
                            } else {
                                blocked.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        int total = allowed.get() + blocked.get();
        assertThat(total).isEqualTo(threads * callsPerThread);
        assertThat(allowed.get()).isEqualTo(maxCalls);
        assertThat(blocked.get()).isEqualTo(threads * callsPerThread - maxCalls);
    }

    @Test
    void allowsAgainAfterOldTimestampsPruned() throws InterruptedException {
        // Use a 2-second window
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("key", 3, 2)).isTrue();
        }
        assertThat(limiter.tryAcquire("key", 3, 2)).isFalse();

        // Wait for window to pass
        // timing-assertion: verifies window expiry after 2-second duration
        Thread.sleep(2100);

        // Should be allowed again
        assertThat(limiter.tryAcquire("key", 3, 2)).isTrue();
    }

    @Test
    void negativeMaxCallsAlwaysAllowed() {
        assertThat(limiter.tryAcquire("key1", -1, 60)).isTrue();
    }

    @Test
    void negativeWindowSecondsAlwaysAllowed() {
        assertThat(limiter.tryAcquire("key1", 5, -1)).isTrue();
    }
}
