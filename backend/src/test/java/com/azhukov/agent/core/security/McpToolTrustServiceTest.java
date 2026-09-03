package com.azhukov.agent.core.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolTrustServiceTest {

    @Test
    void normalizeServerTrustDefaultsMissingToFullAndGarbageToUntrusted() {
        McpToolTrustService service = new McpToolTrustService();

        assertThat(service.normalizeServerTrust(null)).isEqualTo("full");
        assertThat(service.normalizeServerTrust("")).isEqualTo("full");
        assertThat(service.normalizeServerTrust("  Full ")).isEqualTo("full");
        assertThat(service.normalizeServerTrust("UNTRUSTED")).isEqualTo("untrusted");
        assertThat(service.normalizeServerTrust("banana")).isEqualTo("untrusted");
    }

    @Test
    void untrustedWriteCapableToolRequiresApprovalButReadOnlyToolDoesNot() {
        McpToolTrustService service = new McpToolTrustService();

        service.recordServerTools("srv", "untrusted", Map.of(
            "mcp__srv__list_repos", true,
            "mcp__srv__delete_repo", false,
            "mcp__srv__missing_hint", false));

        assertThat(service.requiresApproval("mcp__srv__list_repos")).isFalse();
        assertThat(service.requiresApproval("mcp__srv__delete_repo")).isTrue();
        assertThat(service.requiresApproval("mcp__srv__missing_hint")).isTrue();
    }

    @Test
    void trustedServerDoesNotRequireApprovalForWriteCapableTool() {
        McpToolTrustService service = new McpToolTrustService();

        service.recordTool("srv", "full", "mcp__srv__delete_repo", false);

        assertThat(service.requiresApproval("mcp__srv__delete_repo")).isFalse();
    }

    @Test
    void refreshedServerToolListDropsStaleNativeToolMetadata() {
        McpToolTrustService service = new McpToolTrustService();

        service.recordServerTools("srv", "untrusted", Map.of(
            "mcp__srv__old", false,
            "mcp__srv__new", true));
        service.recordServerTools("srv", "untrusted", Map.of("mcp__srv__new", true));

        assertThat(service.metadata("mcp__srv__old")).isNull();
        assertThat(service.metadata("mcp__srv__new")).isNotNull();
    }
}
