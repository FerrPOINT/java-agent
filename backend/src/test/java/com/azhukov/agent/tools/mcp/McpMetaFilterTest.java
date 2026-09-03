package com.azhukov.agent.tools.mcp;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.client.mcp.McpOAuthManager;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hermes parity tests: MCP tool-result _meta filtering
 * (mcp_tool.py _is_reserved_mcp_meta_key / _strip_reserved_meta_keys,
 * ported from MoonshotAI/kimi-code#2600).
 */
class McpMetaFilterTest {

    @Test
    void reservedPrefixesDropped() {
        // "modelcontextprotocol.io/..." — reserved label + more labels
        assertTrue(McpTool.isReservedMetaKey("modelcontextprotocol.io/cache"));
        // "tools.mcp.com/..." — mcp label followed by more labels
        assertTrue(McpTool.isReservedMetaKey("tools.mcp.com/progress"));
        assertTrue(McpTool.isReservedMetaKey("mcp.internal/id"));
    }

    @Test
    void trailingReservedWordIsVendorNamespace() {
        // "com.example.mcp/..." — mcp is the LAST label before the slash → vendor key
        assertFalse(McpTool.isReservedMetaKey("com.example.mcp/hint"));
        assertFalse(McpTool.isReservedMetaKey("example.mcp/anything"));
    }

    @Test
    void noSlashOrBareWordNotReserved() {
        assertFalse(McpTool.isReservedMetaKey("mcp"));
        assertFalse(McpTool.isReservedMetaKey("vendor/key"));
        assertFalse(McpTool.isReservedMetaKey(""));
    }

    @Test
    void formatMetaFiltersAndKeepsVendorKeys() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("modelcontextprotocol.io/cache", "hit");
        meta.put("tools.mcp.com/progress", "50");
        meta.put("com.example.mcp/hint", "use-pagination");
        String out = McpTool.formatMeta(meta);
        assertFalse(out.contains("cache"));
        assertFalse(out.contains("progress"));
        assertTrue(out.contains("com.example.mcp/hint"));
        assertTrue(out.contains("use-pagination"));
        assertTrue(out.startsWith("[_meta: {"));
    }

    @Test
    void emptyOrNullMetaYieldsEmptySection() {
        assertEquals("", McpTool.formatMeta(null));
        assertEquals("", McpTool.formatMeta(Map.of()));
        // all keys reserved → nothing model-facing
        assertEquals("", McpTool.formatMeta(Map.of("mcp.x/key", "v")));
    }

    @Test
    void executeReturnsFailureForProtocolErrorResult() throws Exception {
        McpLifecycleManager lifecycleManager = mock(McpLifecycleManager.class);
        McpOAuthManager oauthManager = mock(McpOAuthManager.class);
        when(oauthManager.getToken("srv")).thenReturn(Optional.empty());
        when(lifecycleManager.executeTool("srv", "tool", "{}")).thenReturn(
            new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(null, "remote error")),
                true,
                null,
                null));

        ToolResult result = new McpTool(lifecycleManager, oauthManager).execute(
            "{\"server_name\":\"srv\",\"tool_name\":\"tool\"}", null, null);

        assertFalse(result.success());
        assertTrue(result.error().contains("remote error"));
        JsonNode json = errorPayload(result);
        assertFalse(json.get("success").asBoolean());
        assertTrue(json.get("error").asText().contains("remote error"));
        assertEquals(result.error(), json.get("error").asText());
    }

    @Test
    void executeReturnsStructuredFailureForInvalidJson() throws Exception {
        McpLifecycleManager lifecycleManager = mock(McpLifecycleManager.class);
        McpOAuthManager oauthManager = mock(McpOAuthManager.class);

        ToolResult result = new McpTool(lifecycleManager, oauthManager).execute("not-json", null, null);

        assertFalse(result.success());
        JsonNode json = errorPayload(result);
        assertFalse(json.get("success").asBoolean());
        assertTrue(json.get("error").asText().contains("Invalid tool arguments"));
    }

    private JsonNode errorPayload(ToolResult result) throws Exception {
        assertFalse(result.content().isBlank());
        JsonNode json = new ObjectMapper().readTree(result.content());
        assertTrue(json.has("error"));
        return json;
    }
}
