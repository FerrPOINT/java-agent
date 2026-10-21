package com.azhukov.agent.tools.terminal;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChildProcessEnvironmentTest {

    @Test
    void removesInheritedCredentialsAndKeepsRuntimeEssentials() {
        Map<String, String> environment = new HashMap<>(Map.of(
            "PATH", "/usr/bin",
            "HOME", "/root",
            "HUB_GITHUB_TOKEN", "private-token",
            "AGENT_MODEL_API_KEY", "model-key",
            "DB_PASSWORD", "database-password"
        ));

        ChildProcessEnvironment.prepare(environment, Map.of("BUILD_MODE", "test"));

        assertThat(environment)
            .containsEntry("PATH", "/usr/bin")
            .containsEntry("HOME", "/root")
            .containsEntry("BUILD_MODE", "test")
            .doesNotContainKeys("HUB_GITHUB_TOKEN", "AGENT_MODEL_API_KEY", "DB_PASSWORD");
    }

    @Test
    void rejectsSensitiveAndShellControlSessionExports() {
        assertThat(ChildProcessEnvironment.isSafeSessionVariable("BUILD_MODE")).isTrue();
        assertThat(ChildProcessEnvironment.isSafeSessionVariable("GITHUB_TOKEN")).isFalse();
        assertThat(ChildProcessEnvironment.isSafeSessionVariable("PATH")).isFalse();
        assertThat(ChildProcessEnvironment.isSafeSessionVariable("BASH_ENV")).isFalse();
    }
}
