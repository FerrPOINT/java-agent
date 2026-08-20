package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hermes parity tests for the empty-response jittered backoff
 * (conversation_loop.py jittered_backoff: base 5s, cap 60s).
 */
class EmptyResponseBackoffTest {

    @Test
    void attempt1WithinBaseRange() throws Exception {
        long ms = invoke(1);
        // base 5s ±25% → 3750..6250 ms
        assertTrue(ms >= 3_500 && ms <= 6_500, "attempt 1 backoff out of range: " + ms);
    }

    @Test
    void attempt2WithinDoubledRange() throws Exception {
        long ms = invoke(2);
        // base 10s ±25% → 7500..12500
        assertTrue(ms >= 7_000 && ms <= 12_500, "attempt 2 backoff out of range: " + ms);
    }

    @Test
    void cappedAt60Seconds() throws Exception {
        // attempt 3: base 20s ±25% → 15..25s
        long a3 = invoke(3);
        assertTrue(a3 >= 15_000 && a3 <= 25_000, "attempt 3: " + a3);
        // attempt 4: base 40s ±25% → 30..50s
        long a4 = invoke(4);
        assertTrue(a4 >= 30_000 && a4 <= 50_000, "attempt 4: " + a4);
        // attempts ≥5: base would be 80s+, capped to 60s ±25% → 45..75s
        for (int attempt = 5; attempt <= 10; attempt++) {
            long ms = invoke(attempt);
            assertTrue(ms >= 45_000 && ms <= 75_000, "capped attempt " + attempt + ": " + ms);
        }
    }

    @Test
    void jitterVariesAcrossCalls() throws Exception {
        long first = invoke(1);
        boolean varies = false;
        for (int i = 0; i < 20; i++) {
            if (invoke(1) != first) {
                varies = true;
                break;
            }
        }
        assertTrue(varies, "backoff must be jittered, not deterministic");
    }

    private long invoke(int attempt) throws Exception {
        Method m = AgentStreamingService.class.getDeclaredMethod("jitteredBackoffMs", int.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, attempt);
    }
}
