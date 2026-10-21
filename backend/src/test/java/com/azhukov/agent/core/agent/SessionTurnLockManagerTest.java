package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Active and waiting turns must retain the same steer-admission and exclusion fence. */
class SessionTurnLockManagerTest {

    @Test
    void releasingNestedHoldKeepsOuterTurnAdmitted() {
        SessionTurnLockManager manager = new SessionTurnLockManager();
        UUID id = UUID.randomUUID();
        assertThat(manager.tryAcquire(id, 0)).isTrue();
        assertThat(manager.tryAcquire(id, 0)).isTrue();
        try {
            manager.release(id);
            assertThat(manager.runIfLocked(id, () -> true)).isTrue();
        } finally {
            manager.release(id);
            manager.release(id);
        }
        assertThat(manager.runIfLocked(id, () -> true)).isFalse();
    }

    @Test
    void queuedSuccessorRemainsAdmittedAndExcludesAnotherTurn() throws Exception {
        SessionTurnLockManager manager = new SessionTurnLockManager();
        UUID id = UUID.randomUUID();
        assertThat(manager.tryAcquire(id, 0)).isTrue();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var successor = executor.submit(() -> {
                worker.set(Thread.currentThread());
                started.countDown();
                boolean locked = manager.tryAcquire(id, 5);
                if (!locked) return false;
                try {
                    acquired.countDown();
                    return finish.await(5, TimeUnit.SECONDS);
                } finally {
                    manager.release(id);
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(worker.get());
            manager.release(id);
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.runIfLocked(id, () -> true)).isTrue();
            boolean competing = manager.tryAcquire(id, 0);
            if (competing) manager.release(id);
            assertThat(competing).isFalse();
            finish.countDown();
            assertThat(successor.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.runIfLocked(id, () -> true)).isFalse();
        } finally {
            manager.release(id);
            finish.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void timedOutAttemptAndForeignReleaseDoNotEvictTheOwner() throws Exception {
        SessionTurnLockManager manager = new SessionTurnLockManager();
        UUID id = UUID.randomUUID();
        assertThat(manager.tryAcquire(id, 0)).isTrue();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var rejected = executor.submit(() -> {
                manager.release(id);
                return manager.tryAcquire(id, 0);
            });
            assertThat(rejected.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(manager.runIfLocked(id, () -> true)).isTrue();
        } finally {
            manager.release(id);
        }
        assertThat(manager.runIfLocked(id, () -> true)).isFalse();
    }

    @Test
    void interruptedWaiterDoesNotEvictTheOwnerAndPreservesInterrupt() throws Exception {
        SessionTurnLockManager manager = new SessionTurnLockManager();
        UUID id = UUID.randomUUID();
        assertThat(manager.tryAcquire(id, 0)).isTrue();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var rejected = executor.submit(() -> {
                worker.set(Thread.currentThread());
                started.countDown();
                boolean locked = manager.tryAcquire(id, 30);
                interrupted.set(Thread.currentThread().isInterrupted());
                if (locked) manager.release(id);
                return locked;
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(worker.get());
            worker.get().interrupt();
            assertThat(rejected.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(interrupted.get()).isTrue();
            assertThat(manager.runIfLocked(id, () -> true)).isTrue();
        } finally {
            manager.release(id);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(manager.runIfLocked(id, () -> true)).isFalse();
    }

    private static void awaitBlocked(Thread worker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = worker.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) return;
            Thread.sleep(1);
        }
        throw new AssertionError("turn did not wait for the held lock");
    }
}
