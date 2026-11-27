package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class ApiRunAdmissionService {

    public static final int DEFAULT_MAX_CONCURRENT_RUNS = 10;

    private final AgentProperties properties;
    private final ProfileService profiles;
    private final Map<String, AtomicInteger> activeByProfile = new ConcurrentHashMap<>();

    public Optional<Reservation> tryAcquire() {
        return tryAcquire("default", maxConcurrentRuns());
    }

    public Optional<Reservation> tryAcquire(String profile, int limit) {
        if ("default".equals(profile) && limit == 0) {
            return Optional.of(Reservation.noop());
        }
        var activeRuns = activeByProfile.computeIfAbsent(profile, name -> new AtomicInteger());
        while (true) {
            int current = activeRuns.get();
            if (limit > 0 && current >= limit) {
                return Optional.empty();
            }
            if (activeRuns.compareAndSet(current, current + 1)) {
                return Optional.of(new Reservation(activeRuns));
            }
        }
    }

    public int activeRunCount() {
        return activeByProfile.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public int maxConcurrentRuns() {
        AgentProperties.ApiProperties api = properties != null ? properties.getApi() : null;
        int configured = api != null ? api.getMaxConcurrentRuns() : DEFAULT_MAX_CONCURRENT_RUNS;
        return Math.max(0, configured);
    }

    public int maxConcurrentRuns(String profile) {
        int installationDefault = maxConcurrentRuns();
        if ("default".equals(profile)) {
            return installationDefault;
        }
        try {
            return configuredLimit(profiles.readExecutionConfig(profile), installationDefault);
        } catch (IOException | RuntimeException e) {
            throw new ProfileLimitUnavailable();
        }
    }

    static int configuredLimit(Map<String, Object> config, int installationDefault) {
        if (!config.containsKey("max_concurrent_runs")) {
            return installationDefault;
        }
        if (config.get("max_concurrent_runs") instanceof Integer limit && limit >= 0) {
            return limit;
        }
        throw new IllegalArgumentException("max_concurrent_runs must be a non-negative integer");
    }

    public static final class ProfileLimitUnavailable extends IllegalStateException {
        public ProfileLimitUnavailable() {
            super("Profile run limit is unavailable");
        }
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
