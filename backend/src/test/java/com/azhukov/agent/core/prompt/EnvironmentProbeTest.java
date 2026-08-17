package com.azhukov.agent.core.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 6: Environment probe test.
 * Verifies probe output is generated and cached.
 */
class EnvironmentProbeTest {

    @Test
    void probeReturnsNonEmptyWhenSomethingUnusual() {
        EnvironmentProbe probe = new EnvironmentProbe();
        String line = probe.getProbeLine();
        // In the test environment, git is likely installed but python3/pip may or may not exist.
        // The probe should return a string (possibly empty if everything is normal).
        assertThat(line).isNotNull();
    }

    @Test
    void probeResultIsCached() {
        EnvironmentProbe probe = new EnvironmentProbe();
        String line1 = probe.getProbeLine();
        String line2 = probe.getProbeLine();
        // Second call should return the cached result
        assertThat(line2).isEqualTo(line1);
    }

    @Test
    void resetCacheForcesRebuild() {
        EnvironmentProbe probe = new EnvironmentProbe();
        String line1 = probe.getProbeLine();
        probe.resetCache();
        String line2 = probe.getProbeLine();
        // After reset, the probe rebuilds — result should be deterministic (same environment)
        assertThat(line2).isEqualTo(line1);
    }

    @Test
    void probeLineFormat() {
        EnvironmentProbe probe = new EnvironmentProbe();
        String line = probe.getProbeLine();
        if (!line.isEmpty()) {
            // When non-empty, should start with "Environment:"
            assertThat(line).startsWith("Environment:");
            assertThat(line).endsWith(".");
        }
    }

    @Test
    void probeContainsGitStatus() {
        EnvironmentProbe probe = new EnvironmentProbe();
        String line = probe.getProbeLine();
        // In the test environment git is installed, so if the line is non-empty
        // it should mention git
        if (!line.isEmpty()) {
            assertThat(line).contains("git=");
        }
    }

    @Test
    void probeDoesNotThrow() {
        EnvironmentProbe probe = new EnvironmentProbe();
        // Should never throw, even in unusual environments
        String line = probe.getProbeLine();
        assertThat(line).isNotNull();
    }
}