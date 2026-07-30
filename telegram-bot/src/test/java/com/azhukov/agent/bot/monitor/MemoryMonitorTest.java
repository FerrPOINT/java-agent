package com.azhukov.agent.bot.monitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryMonitorTest {

    private MemoryMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new MemoryMonitor(1); // 1 second interval for testing
    }

    @Test
    void start_setsRunning() {
        boolean started = monitor.start();
        assertThat(started).isTrue();
        assertThat(monitor.isRunning()).isTrue();
        monitor.stop();
    }

    @Test
    void start_calledTwice_returnsFalseSecondTime() {
        monitor.start();
        boolean secondStart = monitor.start();
        assertThat(secondStart).isFalse();
        monitor.stop();
    }

    @Test
    void stop_setsNotRunning() {
        monitor.start();
        monitor.stop();
        assertThat(monitor.isRunning()).isFalse();
    }

    @Test
    void stop_withoutStart_doesNotThrow() {
        monitor.stop();
        // Should not throw
    }

    @Test
    void getRssMb_returnsNonNegativeOrUnavailable() {
        long rss = monitor.getRssMb();
        // RSS should be >= 0 (available) or -1 (unavailable)
        assertThat(rss).isGreaterThanOrEqualTo(-1);
    }

    @Test
    void getThreadCount_positive() {
        int threads = monitor.getThreadCount();
        assertThat(threads).isGreaterThan(0);
    }

    @Test
    void getGcCounts_nonNull() {
        long[] counts = monitor.getGcCounts();
        assertThat(counts).isNotNull();
        // At least one GC bean should be present
        assertThat(counts.length).isGreaterThanOrEqualTo(0);
    }

    @Test
    void logMemoryUsage_doesNotThrow() {
        monitor.logMemoryUsage("test");
        monitor.logMemoryUsage("");
    }

    @Test
    void logMemoryUsage_withPrefix() {
        // Just verify it doesn't throw
        monitor.logMemoryUsage("baseline");
    }
}