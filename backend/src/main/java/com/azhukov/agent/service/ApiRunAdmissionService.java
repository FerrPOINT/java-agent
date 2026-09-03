package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class ApiRunAdmissionService {

    public static final int DEFAULT_MAX_CONCURRENT_RUNS = 10;

    private final AgentProperties properties;
    private final AtomicInteger activeRuns = new AtomicInteger();

    public Optional<Reservation> tryAcquire() {
        int limit = maxConcurrentRuns();
        if (limit <= 0) {
            return Optional.of(Reservation.noop());
        }
        while (true) {
            int current = activeRuns.get();
            if (current >= limit) {
                return Optional.empty();
            }
            if (activeRuns.compareAndSet(current, current + 1)) {
                return Optional.of(new Reservation(activeRuns));
            }
        }
    }

    public int activeRunCount() {
        return Math.max(0, activeRuns.get());
    }

    public int maxConcurrentRuns() {
        AgentProperties.ApiProperties api = properties != null ? properties.getApi() : null;
        int configured = api != null ? api.getMaxConcurrentRuns() : DEFAULT_MAX_CONCURRENT_RUNS;
        return Math.max(0, configured);
    }

    public static final class Reservation implements AutoCloseable {
        private static final Reservation NOOP = new Reservation(null);

        private final AtomicInteger activeRuns;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reservation(AtomicInteger activeRuns) {
            this.activeRuns = activeRuns;
        }

        static Reservation noop() {
            return NOOP;
        }

        @Override
        public void close() {
            if (activeRuns != null && closed.compareAndSet(false, true)) {
                activeRuns.updateAndGet(value -> Math.max(0, value - 1));
            }
        }
    }
}
