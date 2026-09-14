package com.azhukov.agent.client.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upstream parity (Hermes ef0136385c, #81331): MCP tool names longer than 64
 * chars are clamped with a deterministic sha256 hash suffix — distinct long
 * names never collide, identical inputs always produce the same output, and
 * short names pass through untouched.
 */
class McpToolNameClampTest {

    @Test
    void shortNamesPassThroughUnchanged() {
        assertThat(McpLifecycleManager.mcpPrefixedToolName("my-server", "read-file"))
            .isEqualTo("mcp__my_server__read_file");
    }

    @Test
    void longNamesClampTo64WithStableHashSuffix() {
        String server = "agent_plugin_" + "s".repeat(30);
        String tool = "reply_communication_todo";
        String name = McpLifecycleManager.mcpPrefixedToolName(server, tool);
        assertThat(name).hasSize(64);
        assertThat(name).startsWith("mcp__");
        // deterministic across calls
        assertThat(McpLifecycleManager.mcpPrefixedToolName(server, tool))
            .isEqualTo(name);
    }

    @Test
    void distinctLongNamesDoNotCollide() {
        String server = "agent_plugin_" + "s".repeat(30);
        String a = McpLifecycleManager.mcpPrefixedToolName(server, "reply_communication_todo");
        String b = McpLifecycleManager.mcpPrefixedToolName(server, "reply_communication_task");
        assertThat(a).hasSize(64);
        assertThat(b).hasSize(64);
        // same clamped prefix, different hash suffix
        assertThat(a).isNotEqualTo(b);
        assertThat(a.substring(0, 40)).isEqualTo(b.substring(0, 40));
        assertThat(a.substring(56)).isNotEqualTo(b.substring(56));
    }

    @Test
    void exact64CharNamePassesThrough() {
        String exactly64 = "mcp__" + "a".repeat(59); // 5 + 59 = 64
        assertThat(exactly64).hasSize(64);
        assertThat(McpLifecycleManager.clampMcpToolName(exactly64)).isSameAs(exactly64);
    }
}
