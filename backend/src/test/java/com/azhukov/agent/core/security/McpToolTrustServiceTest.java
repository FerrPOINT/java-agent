package com.azhukov.agent.core.security;

import io.modelcontextprotocol.spec.McpSchema;
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
            "mcp__srv__list_repos", tool("list_repos", true),
            "mcp__srv__delete_repo", tool("delete_repo", false),
            "mcp__srv__missing_hint", McpSchema.Tool.builder("missing_hint").build()));

        assertThat(service.metadata("mcp__srv__list_repos").requiresApproval()).isFalse();
        assertThat(service.metadata("mcp__srv__delete_repo").requiresApproval()).isTrue();
        assertThat(service.metadata("mcp__srv__missing_hint").requiresApproval()).isTrue();
    }

    @Test
    void trustedServerDoesNotRequireApprovalForWriteCapableTool() {
        McpToolTrustService service = new McpToolTrustService();

        service.recordServerTools("srv", "full", Map.of("mcp__srv__delete_repo", tool("delete_repo", false)));

        assertThat(service.metadata("mcp__srv__delete_repo").requiresApproval()).isFalse();
    }

    @Test
    void refreshedServerToolListDropsStaleNativeToolMetadata() {
        McpToolTrustService service = new McpToolTrustService();

        service.recordServerTools("srv", "untrusted", Map.of(
            "mcp__srv__old", tool("old", false),
            "mcp__srv__new", tool("new", true)));
        service.recordServerTools("srv", "untrusted", Map.of("mcp__srv__new", tool("new", true)));

        assertThat(service.metadata("mcp__srv__old")).isNull();
        assertThat(service.metadata("mcp__srv__new")).isNotNull();
        service.recordServerTools("srv", null, Map.of());
        assertThat(service.metadata("mcp__srv__new")).isNull();
    }
    private static McpSchema.Tool tool(String name, boolean readOnly) {
        return McpSchema.Tool.builder(name)
            .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly).build()).build();
    }
}
