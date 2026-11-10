package com.azhukov.agent.core.security;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;


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

        service.recordServerTools("srv", "untrusted", List.of(
            tool("list_repos", true),
            tool("delete_repo", false),
            McpSchema.Tool.builder("missing_hint").build()));

        assertThat(service.metadata("mcp__srv__list_repos").requiresApproval()).isFalse();
        assertThat(service.metadata("mcp__srv__delete_repo").requiresApproval()).isTrue();
        assertThat(service.metadata("mcp__srv__missing_hint").requiresApproval()).isTrue();
    }

    @Test
    void trustedServerDoesNotRequireApprovalForWriteCapableTool() {
        McpToolTrustService service = new McpToolTrustService();

        service.recordServerTools("srv", "full", List.of(tool("delete_repo", false)));

        assertThat(service.metadata("mcp__srv__delete_repo").requiresApproval()).isFalse();
    }

    @Test
    void refreshedServerToolListDropsStaleNativeToolMetadata() {
        McpToolTrustService service = new McpToolTrustService();

        service.recordServerTools("srv", "untrusted", List.of(
            tool("old", false),
            tool("new", true)));
        service.recordServerTools("srv", "untrusted", List.of(tool("new", true)));

        assertThat(service.metadata("mcp__srv__old")).isNull();
        assertThat(service.metadata("mcp__srv__new")).isNotNull();
        service.recordServerTools("srv", null, List.of());
        assertThat(service.metadata("mcp__srv__new")).isNull();
    }
    private static McpSchema.Tool tool(String name, boolean readOnly) {
        return McpSchema.Tool.builder(name)
            .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly).build()).build();
    }
}
