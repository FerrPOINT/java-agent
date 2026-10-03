package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SessionMutationLockTest {

    @Test
    void serializesPersistenceAndDeletionForTheSameSession() throws Exception {
        UUID sessionId = UUID.randomUUID();
        CountDownLatch persistenceEntered = new CountDownLatch(1);
        CountDownLatch allowPersistenceToFinish = new CountDownLatch(1);
        CountDownLatch deletionEntered = new CountDownLatch(1);
        AtomicBoolean deletedBeforePersistenceFinished = new AtomicBoolean(false);

        Thread persistence = Thread.ofVirtual().start(() ->
            SessionMutationLock.withLock(sessionId, () -> {
                persistenceEntered.countDown();
                await(allowPersistenceToFinish);
                return null;
            }));
        assertThat(persistenceEntered.await(5, TimeUnit.SECONDS)).isTrue();

        Thread deletion = Thread.ofVirtual().start(() ->
            SessionMutationLock.withLock(sessionId, () -> {
                deletedBeforePersistenceFinished.set(allowPersistenceToFinish.getCount() > 0);
                deletionEntered.countDown();
                return null;
            }));

        assertThat(deletionEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();
        allowPersistenceToFinish.countDown();
        assertThat(deletionEntered.await(5, TimeUnit.SECONDS)).isTrue();
        persistence.join();
        deletion.join();
        assertThat(deletedBeforePersistenceFinished).isFalse();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
