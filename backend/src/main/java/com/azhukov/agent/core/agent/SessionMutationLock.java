package com.azhukov.agent.core.agent;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serializes session deletion with in-process message persistence. */
public final class SessionMutationLock {

    private static final int STRIPES = 256;
    private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private SessionMutationLock() {
    }

    public static <T> T withLock(UUID sessionId, Supplier<T> operation) {
        ReentrantLock lock = LOCKS[Math.floorMod(sessionId.hashCode(), STRIPES)];
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }
}
