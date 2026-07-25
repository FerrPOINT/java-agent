package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultFileSafetyTest {

    @Test
    void allowsPathsInsideAllowedBase() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().setAllowedPaths(List.of("/tmp/agent-work"));
        DefaultFileSafety safety = new DefaultFileSafety(properties);
        assertThat(safety.isPathAllowed(Paths.get("/tmp/agent-work/file.txt"))).isTrue();
        assertThat(safety.isPathAllowed(Paths.get("/tmp/agent-work/subdir/file.txt"))).isTrue();
    }

    @Test
    void blocksPathsOutsideAllowedBase() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().setAllowedPaths(List.of("/tmp/agent-work"));
        DefaultFileSafety safety = new DefaultFileSafety(properties);
        assertThat(safety.isPathAllowed(Paths.get("/etc/passwd"))).isFalse();
        assertThat(safety.isPathAllowed(Paths.get("/tmp/other/file.txt"))).isFalse();
    }

    @Test
    void allowsEverythingWhenAllowedPathsEmpty() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(true);
        DefaultFileSafety safety = new DefaultFileSafety(properties);
        assertThat(safety.isPathAllowed(Paths.get("/any/path"))).isTrue();
    }

    @Test
    void skipsCheckWhenDisabled() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(false);
        DefaultFileSafety safety = new DefaultFileSafety(properties);
        assertThat(safety.isPathAllowed(Paths.get("/any/path"))).isTrue();
    }

    @Test
    void blocksCommandsContainingBlockedSubstrings() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().setBlockedCommands(List.of("rm -rf", "mkfs"));
        DefaultFileSafety safety = new DefaultFileSafety(properties);
        assertThat(safety.isCommandAllowed("rm -rf /")).isFalse();
        assertThat(safety.isCommandAllowed("echo hello")).isTrue();
    }
}
