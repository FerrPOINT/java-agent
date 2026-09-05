package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * DEBT-1: the OSV malware gate is wired into stdio MCP server creation.
 * A package with known MAL-* advisories must refuse to launch.
 */
class McpLifecycleManagerOsvGateTest {

    private AgentProperties.McpProperties.ServerProperties stdioServer(String command, String arg) {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("osv-test");
        server.setTransport("stdio");
        server.setCommand(command);
        server.getArgs().add(arg);
        return server;
    }

    @Test
    void malwarePackageIsRefusedBeforeLaunch() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().setOsvCheckEnabled(false); // default gate off → we inject a stub below

        McpLifecycleManager manager = new McpLifecycleManager(
            properties, new ObjectMapper(), mock(ApplicationContext.class), null, null, null, null, null);
        manager.init(); // osvCheckService == null (disabled)

        // Re-enable through the derived field the way the production path would,
        // but backed by a stub service that always reports malware.
        manager.setOsvCheckServiceForTesting(new com.azhukov.agent.core.security.OsvCheckService(true) {
            @Override
            public String checkPackageForMalware(String command, java.util.List<String> args) {
                return "BLOCKED: Package 'stub-malware' (npm) has known malware advisories: MAL-2026-9999.";
            }
        });

        assertThatThrownBy(() -> manager.createClient(stdioServer("npx", "stub-malware")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("OSV malware check")
            .hasMessageContaining("MAL-2026-9999");
    }

    @Test
    void disabledGateAllowsLaunch() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().setOsvCheckEnabled(false);

        McpLifecycleManager manager = new McpLifecycleManager(
            properties, new ObjectMapper(), mock(ApplicationContext.class), null, null, null, null, null);
        manager.init();
        assertThat(manager.osvGate()).isNull();

        // connect() with a nonexistent binary still reaches launch (validation passes,
        // OSV gate skipped) — failure comes from the process itself, not from the gate.
        manager.connect(stdioServer("/nonexistent-binary-xyz", "some-package"));
        assertThat(manager.isConnected("osv-test")).isFalse();
    }
}
