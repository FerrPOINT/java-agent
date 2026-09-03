package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.security.McpResponseScanner;
import com.azhukov.agent.core.security.McpToolDefinitionScanner;
import com.azhukov.agent.core.security.McpToolTrustService;
import com.azhukov.agent.core.security.SlidingWindowRateLimiter;
import com.azhukov.agent.core.security.ToolArgumentInjectionScanner;
import com.azhukov.agent.core.security.ToolFingerprintStore;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.HttpRequest;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpLifecycleManagerNewFeaturesTest {

    @Test
    void sanitizeError_stripsBearerTokens() {
        assertThat(McpLifecycleManager.sanitizeError("Bearer sk-abc123 error"))
            .contains("[REDACTED]")
            .doesNotContain("sk-abc123");
    }

    @Test
    void sanitizeError_stripsGitHubTokens() {
        assertThat(McpLifecycleManager.sanitizeError("ghp_abcdef123456 leaked"))
            .contains("[REDACTED]")
            .doesNotContain("ghp_abcdef123456");
    }

    @Test
    void sanitizeError_stripsPasswordParams() {
        assertThat(McpLifecycleManager.sanitizeError("password=secret123 failed"))
            .contains("[REDACTED]")
            .doesNotContain("secret123");
    }

    @Test
    void sanitizeError_stripsKeyParams() {
        assertThat(McpLifecycleManager.sanitizeError("key=mysecretkey error"))
            .contains("[REDACTED]")
            .doesNotContain("mysecretkey");
    }

    @Test
    void sanitizeError_stripsSecretParams() {
        assertThat(McpLifecycleManager.sanitizeError("secret=topsecret error"))
            .contains("[REDACTED]")
            .doesNotContain("topsecret");
    }

    @Test
    void sanitizeError_returnsNullForNull() {
        assertThat(McpLifecycleManager.sanitizeError(null)).isNull();
    }

    @Test
    void sanitizeError_returnsEmptyForEmpty() {
        assertThat(McpLifecycleManager.sanitizeError("")).isEmpty();
    }

    @Test
    void sanitizeError_preservesNonCredentialText() {
        assertThat(McpLifecycleManager.sanitizeError("Connection refused"))
            .isEqualTo("Connection refused");
    }

    @Test
    void buildSafeEnv_filtersToSafeKeys() {
        // userEnv provides explicit vars
        Map<String, String> userEnv = Map.of("MY_API_KEY", "secret123", "CUSTOM_VAR", "val");
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(userEnv);

        // User-specified env vars should be passed through
        assertThat(result).containsEntry("MY_API_KEY", "secret123");
        assertThat(result).containsEntry("CUSTOM_VAR", "val");

        // PATH should be in the safe set (it's in the process environment)
        if (System.getenv().containsKey("PATH")) {
            assertThat(result).containsKey("PATH");
        }
        // HOME should be safe
        if (System.getenv().containsKey("HOME")) {
            assertThat(result).containsKey("HOME");
        }
    }

    @Test
    void buildSafeEnv_passesXdgVars() {
        // XDG_ vars should pass through from the process environment
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(Map.of());
        // If XDG_RUNTIME_DIR is set in the test environment, it should pass
        for (String key : System.getenv().keySet()) {
            if (key.startsWith("XDG_")) {
                assertThat(result).containsKey(key);
            }
        }
    }

    @Test
    void buildSafeEnv_doesNotLeakUnsafeVars() {
        // Set a fake env var in userEnv - it should pass through (user explicitly specified it)
        // But process env vars that are not in the safe set should NOT be leaked
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(null);
        // Check that potentially sensitive env vars are not included (unless in safe set)
        for (String key : System.getenv().keySet()) {
            if (!key.equals("PATH") && !key.equals("HOME") && !key.equals("USER")
                && !key.equals("LANG") && !key.equals("LC_ALL") && !key.equals("TERM")
                && !key.equals("SHELL") && !key.equals("TMPDIR") && !key.startsWith("XDG_")) {
                assertThat(result).doesNotContainKey(key);
            }
        }
    }

    @Test
    void buildSafeEnv_handlesNullUserEnv() {
        Map<String, String> result = McpLifecycleManager.buildSafeEnv(null);
        // Should not throw, should contain safe vars from process env
        assertThat(result).isNotNull();
    }

    @Test
    void refreshTools_updatesStateWhenToolListChanges() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx, new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(), new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()), new SlidingWindowRateLimiter());

        // Inject a client with initial tools
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool1 = McpSchema.Tool.builder("tool1").title("t").description("d").inputSchema(Map.of()).build();
        injectClient(manager, "srv", client, List.of(tool1));

        // Simulate server now returning different tools
        McpSchema.Tool tool2 = McpSchema.Tool.builder("tool2").title("t").description("d").inputSchema(Map.of()).build();
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool2), ""));

        manager.refreshTools("srv");

        // Verify stale tool was deregistered and new tool was registered
        verify(registry).deregisterDynamic("mcp__srv__tool1");
        verify(registry).registerDynamic(eq("mcp__srv__tool2"), eq("mcp-srv"), any(), any());

        // Verify state was updated
        List<McpLifecycleManager.McpServerInfo> servers = manager.listServers();
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).toolNames()).containsExactly("tool2");
    }

    @Test
    void refreshTools_drainsPaginatedToolList() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool oldTool = McpSchema.Tool.builder("old").title("t").description("d").inputSchema(Map.of()).build();
        McpSchema.Tool pageOneTool = McpSchema.Tool.builder("page-one").title("t").description("d").inputSchema(Map.of()).build();
        McpSchema.Tool pageTwoTool = McpSchema.Tool.builder("page-two").title("t").description("d").inputSchema(Map.of()).build();
        injectClient(manager, "srv", client, List.of(oldTool));

        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(pageOneTool), "cursor-1"));
        when(client.listTools("cursor-1")).thenReturn(new McpSchema.ListToolsResult(List.of(pageTwoTool), null));

        manager.refreshTools("srv");

        verify(client).listTools("cursor-1");
        verify(registry).deregisterDynamic("mcp__srv__old");
        verify(registry).registerDynamic(eq("mcp__srv__page_one"), eq("mcp-srv"), any(), any());
        verify(registry).registerDynamic(eq("mcp__srv__page_two"), eq("mcp-srv"), any(), any());
        assertThat(manager.listServers().get(0).toolNames()).containsExactly("page-one", "page-two");
    }

    @Test
    void refreshTools_registersResourceAndPromptUtilityToolsWhenCapabilitiesAdvertised() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool oldTool = McpSchema.Tool.builder("old").title("t").description("d").inputSchema(Map.of()).build();
        McpSchema.Tool tool = McpSchema.Tool.builder("tool").title("t").description("d").inputSchema(Map.of()).build();
        injectClient(manager, "srv", client, List.of(oldTool));

        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        when(client.getServerCapabilities()).thenReturn(new McpSchema.ServerCapabilities(
            null,
            Map.of(),
            null,
            new McpSchema.ServerCapabilities.PromptCapabilities(false),
            new McpSchema.ServerCapabilities.ResourceCapabilities(false, false),
            null
        ));
        when(client.listResources()).thenReturn(new McpSchema.ListResourcesResult(List.of(
            new McpSchema.Resource("resource://one", "one", null, "First", "text/plain", null, null, null)
        ), null));
        when(client.readResource(any(McpSchema.ReadResourceRequest.class))).thenReturn(
            new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents("resource://one", "text/plain", "hello")
            )));
        when(client.listPrompts()).thenReturn(new McpSchema.ListPromptsResult(List.of(
            new McpSchema.Prompt("summarize", null, "Summarize", List.of(
                new McpSchema.PromptArgument("topic", null, "Topic", true)
            ))
        ), null));
        when(client.getPrompt(any(McpSchema.GetPromptRequest.class))).thenReturn(
            new McpSchema.GetPromptResult("Prompt description", List.of(
                new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent("hi"))
            )));

        manager.refreshTools("srv");

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ToolHandler> handlerCaptor = ArgumentCaptor.forClass(ToolHandler.class);
        verify(registry, atLeast(5)).registerDynamic(nameCaptor.capture(), eq("mcp-srv"), any(), handlerCaptor.capture());

        Map<String, ToolHandler> handlers = new java.util.HashMap<>();
        List<String> names = nameCaptor.getAllValues();
        List<ToolHandler> capturedHandlers = handlerCaptor.getAllValues();
        for (int i = 0; i < names.size(); i++) {
            handlers.put(names.get(i), capturedHandlers.get(i));
        }

        assertThat(handlers).containsKeys(
            "mcp__srv__list_resources",
            "mcp__srv__read_resource",
            "mcp__srv__list_prompts",
            "mcp__srv__get_prompt"
        );
        assertThat(handlers.get("mcp__srv__list_resources").execute("{}", null, null).content())
            .contains("\"resources\"")
            .contains("resource://one")
            .contains("\"mimeType\":\"text/plain\"");
        assertThat(handlers.get("mcp__srv__read_resource").execute("{\"uri\":\"resource://one\"}", null, null).content())
            .contains("\"result\":\"hello\"");
        ToolResult missingUri = handlers.get("mcp__srv__read_resource").execute("{}", null, null);
        assertThat(missingUri.success()).isFalse();
        assertJsonError(missingUri).contains("Missing required parameter 'uri'");
        assertThat(handlers.get("mcp__srv__list_prompts").execute("{}", null, null).content())
            .contains("\"prompts\"")
            .contains("\"name\":\"summarize\"")
            .contains("\"required\":true");
        assertThat(handlers.get("mcp__srv__get_prompt").execute("{\"name\":\"summarize\",\"arguments\":{\"topic\":\"x\"}}", null, null).content())
            .contains("\"description\":\"Prompt description\"")
            .contains("\"role\":\"user\"")
            .contains("\"content\":\"hi\"");
    }

    @Test
    void refreshTools_keepsNativeToolWhenGeneratedUtilityNameWouldCollide() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool oldTool = McpSchema.Tool.builder("old").title("t").description("d").inputSchema(Map.of()).build();
        McpSchema.Tool nativeReadResource = McpSchema.Tool.builder("read_resource")
            .title("t").description("native").inputSchema(Map.of()).build();
        injectClient(manager, "srv", client, List.of(oldTool));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(nativeReadResource), null));
        when(client.getServerCapabilities()).thenReturn(new McpSchema.ServerCapabilities(
            null,
            Map.of(),
            null,
            null,
            new McpSchema.ServerCapabilities.ResourceCapabilities(false, false),
            null
        ));

        manager.refreshTools("srv");

        verify(registry, times(1)).registerDynamic(eq("mcp__srv__read_resource"), eq("mcp-srv"), any(), any());
    }

    @Test
    void refreshTools_recordsTrustAndReadOnlyHintsForApprovalGate() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());
        McpToolTrustService trustService = new McpToolTrustService();
        injectMcpToolTrustService(manager, trustService);

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool readOnly = McpSchema.Tool.builder("list_repos")
            .title("List repositories")
            .description("Read repository list")
            .inputSchema(Map.of())
            .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).build())
            .build();
        McpSchema.Tool writeCapable = McpSchema.Tool.builder("delete_repo")
            .title("Delete repository")
            .description("Delete a repository")
            .inputSchema(Map.of())
            .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false).build())
            .build();
        injectClient(manager, "srv", client, List.of(), "untrusted");
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(readOnly, writeCapable), null));

        manager.refreshTools("srv");

        assertThat(trustService.serverTrust("srv")).isEqualTo("untrusted");
        assertThat(trustService.requiresApproval("mcp__srv__delete_repo")).isTrue();
        assertThat(trustService.requiresApproval("mcp__srv__list_repos")).isFalse();
        verify(registry).registerDynamic(eq("mcp__srv__delete_repo"), eq("mcp-srv"), any(), any());
        verify(registry).registerDynamic(eq("mcp__srv__list_repos"), eq("mcp-srv"), any(), any());
    }

    @Test
    void refreshTools_includeFilterTakesPrecedenceOverExclude() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "ink", client, List.of(), "full", List.of("create_service"),
            List.of("create_service", "delete_service"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
            tool("create_service"),
            tool("delete_service"),
            tool("list_services")
        ), null));

        manager.refreshTools("ink");

        verify(registry).registerDynamic(eq("mcp__ink__create_service"), eq("mcp-ink"), any(), any());
        verify(registry, never()).registerDynamic(eq("mcp__ink__delete_service"), any(), any(), any());
        verify(registry, never()).registerDynamic(eq("mcp__ink__list_services"), any(), any(), any());
    }

    @Test
    void refreshTools_emptyIncludeRegistersNoNativeTools() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "ink", client, List.of(), "full", List.of(), null);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
            tool("create_service"),
            tool("delete_service"),
            tool("list_services")
        ), null));

        manager.refreshTools("ink");

        verify(registry, never()).registerDynamic(any(), any(), any(), any());
    }

    @Test
    void refreshTools_excludeFilterSupportsGlobPatterns() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "ink", client, List.of(), "full", null, List.of("delete_*", "*_radar_*"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
            tool("create_service"),
            tool("delete_service"),
            tool("daily_radar_scan")
        ), null));

        manager.refreshTools("ink");

        verify(registry).registerDynamic(eq("mcp__ink__create_service"), eq("mcp-ink"), any(), any());
        verify(registry, never()).registerDynamic(eq("mcp__ink__delete_service"), any(), any(), any());
        verify(registry, never()).registerDynamic(eq("mcp__ink__daily_radar_scan"), any(), any(), any());
    }

    @Test
    void matchesNameFilterSupportsExactAndGlobPatterns() {
        assertThat(McpLifecycleManager.matchesNameFilter("create_service", List.of("create_service"))).isTrue();
        assertThat(McpLifecycleManager.matchesNameFilter("daily_radar_scan", List.of("*_radar_*"))).isTrue();
        assertThat(McpLifecycleManager.matchesNameFilter("daily_radar_scan", List.of("daily_?????_scan"))).isTrue();
        assertThat(McpLifecycleManager.matchesNameFilter("list_services", List.of("create_*"))).isFalse();
    }

    @Test
    void truncateMcpTextResultLeavesOrdinaryLargeResultsUntouched() {
        String text = "z".repeat(60_000);

        assertThat(McpLifecycleManager.truncateMcpTextResult(text)).isSameAs(text);
    }

    @Test
    void truncateMcpTextResultPreservesHeadTailAndCountsOmittedChars() {
        String text = "H".repeat(40) + "M".repeat(5_000) + "T".repeat(60);

        String result = McpLifecycleManager.truncateMcpTextResult(text, 100);

        assertThat(result.substring(0, 40)).isEqualTo("H".repeat(40));
        assertThat(result).endsWith("T".repeat(60));
        assertThat(result).contains("5,000 chars omitted out of 5,100 total");
    }

    @Test
    void mcpToolHandlerTruncatesPathologicalSuccessfulTextResult() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        String huge = "H".repeat(800_000) + "M".repeat(500_500) + "T".repeat(1_200_000);
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, huge)), false, null, null));
        injectClient(manager, "srv", client, List.of());

        ToolResult result = manager.new McpToolHandler("srv", "tool").execute("{}", null, null);

        assertThat(result.success()).isTrue();
        JsonNode json = objectMapper.readTree(result.content());
        String text = json.get("result").asText();
        assertThat(text).contains("MCP RESULT TRUNCATED");
        assertThat(text.length()).isLessThan(huge.length());
        assertThat(text).startsWith("H".repeat(80));
        assertThat(text).endsWith("T".repeat(80));
    }

    @Test
    void mcpImageExtensionForMimeTypeMapsJpegVariants() {
        assertThat(McpLifecycleManager.mcpImageExtensionForMimeType("image/jpeg")).isEqualTo(".jpg");
        assertThat(McpLifecycleManager.mcpImageExtensionForMimeType("image/jpg")).isEqualTo(".jpg");
        assertThat(McpLifecycleManager.mcpImageExtensionForMimeType("IMAGE/JPEG")).isEqualTo(".jpg");
        assertThat(McpLifecycleManager.mcpImageExtensionForMimeType("image/jpeg; charset=utf-8")).isEqualTo(".jpg");
    }

    @Test
    void mcpImageExtensionForMimeTypeUnknownDefaultsToPng() {
        assertThat(McpLifecycleManager.mcpImageExtensionForMimeType("")).isEqualTo(".png");
        assertThat(McpLifecycleManager.mcpImageExtensionForMimeType("image/unheard-of-format")).isEqualTo(".png");
    }

    @Test
    void cacheMcpImageContentValidPngReturnsMediaTagAndWritesBytes() throws Exception {
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        byte[] expected = Base64.getDecoder().decode(png);

        String tag = McpLifecycleManager.cacheMcpImageContent(new McpSchema.ImageContent(null, png, "image/png"));

        assertThat(tag).startsWith("MEDIA:");
        Path path = Path.of(tag.substring("MEDIA:".length()));
        try {
            assertThat(path.getFileName().toString()).endsWith(".png");
            assertThat(Files.readAllBytes(path)).isEqualTo(expected);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void cacheMcpImageContentRejectsNonImageMime() {
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

        assertThat(McpLifecycleManager.cacheMcpImageContent(
            new McpSchema.ImageContent(null, png, "text/html"))).isEmpty();
    }

    @Test
    void cacheMcpImageContentRejectsMissingData() {
        assertThat(McpLifecycleManager.cacheMcpImageContent(
            new McpSchema.ImageContent(null, "", "image/png"))).isEmpty();
    }

    @Test
    void cacheMcpImageContentRejectsHtmlMasqueradingAsImage() {
        String html = Base64.getEncoder().encodeToString("<html></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(McpLifecycleManager.cacheMcpImageContent(
            new McpSchema.ImageContent(null, html, "image/png"))).isEmpty();
    }

    @Test
    void cacheMcpImageContentValidJpegUsesJpgExtension() throws Exception {
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, (byte) 0xFF, (byte) 0xD9};
        String data = Base64.getEncoder().encodeToString(jpeg);

        String tag = McpLifecycleManager.cacheMcpImageContent(new McpSchema.ImageContent(null, data, "image/jpeg"));

        assertThat(tag).startsWith("MEDIA:");
        Path path = Path.of(tag.substring("MEDIA:".length()));
        try {
            assertThat(path.getFileName().toString()).endsWith(".jpg");
            assertThat(Files.readAllBytes(path)).isEqualTo(jpeg);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void mcpResourceFilenameUsesUriLastSegment() {
        assertThat(McpLifecycleManager.mcpResourceFilename(
            "slack://files/ABC/quarterly.pdf", "application/pdf")).isEqualTo("quarterly.pdf");
    }

    @Test
    void mcpResourceFilenameCapsLongNamePreservingExtension() {
        String name = McpLifecycleManager.mcpResourceFilename(
            "x://host/" + "a".repeat(500) + ".pdf", "application/pdf");

        assertThat(name).hasSizeLessThanOrEqualTo(150);
        assertThat(name).endsWith(".pdf");
    }

    @Test
    void renderMcpBlobResourceContentsMaterializesPdfBlob() throws Exception {
        byte[] pdf = "%PDF-1.4 fake pdf payload for tests".getBytes(StandardCharsets.US_ASCII);
        McpSchema.BlobResourceContents resource = new McpSchema.BlobResourceContents(
            "slack://files/F123/report.pdf", "application/pdf", Base64.getEncoder().encodeToString(pdf));

        String out = McpLifecycleManager.renderMcpBlobResourceContents(resource);

        assertThat(out).contains("saved to").contains("application/pdf").contains("read_file");
        Path path = resourcePathFromRendered(out);
        try {
            assertThat(path.getFileName().toString()).contains("report.pdf");
            assertThat(Files.readAllBytes(path)).isEqualTo(pdf);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void renderMcpBlobResourceContentsMalformedBase64FailsExplicitly() {
        McpSchema.BlobResourceContents resource = new McpSchema.BlobResourceContents(
            "x://y/report.pdf", "application/pdf", "!!!not-base64!!!");

        assertThat(McpLifecycleManager.renderMcpBlobResourceContents(resource))
            .contains("could not be decoded")
            .contains("application/pdf");
    }

    @Test
    void renderMcpBlobResourceContentsPathTraversalUriIsNeutralized() throws Exception {
        byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
        McpSchema.BlobResourceContents resource = new McpSchema.BlobResourceContents(
            "evil://host/../../etc/passwd", "application/pdf", Base64.getEncoder().encodeToString(pdf));

        String out = McpLifecycleManager.renderMcpBlobResourceContents(resource);
        Path path = resourcePathFromRendered(out);
        try {
            assertThat(path.getFileName().toString()).contains("passwd");
            assertThat(path.toString()).doesNotContain("..");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void cacheMcpAudioContentRejectsNonAudioMime() {
        String data = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.US_ASCII));

        assertThat(McpLifecycleManager.cacheMcpAudioContent(
            new McpSchema.AudioContent(null, data, "application/pdf"))).isEmpty();
    }

    @Test
    void cacheMcpAudioContentAudioWavCachedAsMedia() throws Exception {
        byte[] wav = "RIFFfakewav".getBytes(StandardCharsets.US_ASCII);
        String data = Base64.getEncoder().encodeToString(wav);

        String tag = McpLifecycleManager.cacheMcpAudioContent(new McpSchema.AudioContent(null, data, "audio/wav"));

        assertThat(tag).startsWith("MEDIA:");
        Path path = Path.of(tag.substring("MEDIA:".length()));
        try {
            assertThat(path.getFileName().toString()).endsWith(".wav");
            assertThat(Files.readAllBytes(path)).isEqualTo(wav);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void formatCallToolResultPreservesMixedMediaResourceOrdering() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());
        byte[] wav = "RIFFfakewav".getBytes(StandardCharsets.US_ASCII);
        byte[] pdf = "%PDF-1.4 fake pdf payload for tests".getBytes(StandardCharsets.US_ASCII);

        String payload = manager.formatCallToolResult(new McpSchema.CallToolResult(List.of(
            new McpSchema.TextContent(null, "File ID: F123\nMIME Type: application/pdf"),
            new McpSchema.AudioContent(null, Base64.getEncoder().encodeToString(wav), "audio/wav"),
            new McpSchema.EmbeddedResource(null, new McpSchema.BlobResourceContents(
                "slack://files/F123/report.pdf", "application/pdf", Base64.getEncoder().encodeToString(pdf)))
        ), false, null, null), "slack");
        String text = objectMapper.readTree(payload).get("result").asText();
        Path audioPath = mediaPathFromText(text);
        Path resourcePath = resourcePathFromRendered(text);

        try {
            assertThat(text).startsWith("File ID: F123");
            assertThat(text.indexOf("MEDIA:")).isGreaterThan(text.indexOf("File ID: F123"));
            assertThat(text.indexOf("[MCP resource saved to")).isGreaterThan(text.indexOf("MEDIA:"));
            assertThat(Files.readAllBytes(audioPath)).isEqualTo(wav);
            assertThat(Files.readAllBytes(resourcePath)).isEqualTo(pdf);
        } finally {
            Files.deleteIfExists(audioPath);
            Files.deleteIfExists(resourcePath);
        }
    }

    @Test
    void resolveMcpToolTimeoutDefaultsToHermesDefault() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null,
            null, null, null, null, null);

        assertThat(manager.resolveMcpToolTimeout(new AgentProperties.McpProperties.ServerProperties()))
            .isEqualTo(java.time.Duration.ofSeconds(300));
    }

    @Test
    void resolveMcpToolTimeoutUsesGlobalToolCallWhenServerTimeoutAbsent() {
        AgentProperties properties = new AgentProperties();
        properties.getTimeouts().getMcp().setToolCall(120);
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null,
            null, null, null, null, null);

        assertThat(manager.resolveMcpToolTimeout(new AgentProperties.McpProperties.ServerProperties()))
            .isEqualTo(java.time.Duration.ofSeconds(120));
    }

    @Test
    void resolveMcpToolTimeoutPerServerTimeoutWinsOverGlobal() {
        AgentProperties properties = new AgentProperties();
        properties.getTimeouts().getMcp().setToolCall(120);
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setTimeout(45);
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null,
            null, null, null, null, null);

        assertThat(manager.resolveMcpToolTimeout(server)).isEqualTo(java.time.Duration.ofSeconds(45));
    }

    @Test
    void resolveMcpToolTimeoutKeepsLegacyTimeoutSecondsAlias() {
        AgentProperties properties = new AgentProperties();
        properties.getTimeouts().getMcp().setToolCall(120);
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setTimeoutSeconds(30);
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null,
            null, null, null, null, null);

        assertThat(manager.resolveMcpToolTimeout(server)).isEqualTo(java.time.Duration.ofSeconds(30));
    }

    @Test
    void resolveMcpToolTimeoutInvalidValuesFallBackToDefault() {
        AgentProperties properties = new AgentProperties();
        properties.getTimeouts().getMcp().setToolCall(-1);
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setTimeout(0);
        server.setTimeoutSeconds(-5);
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null,
            null, null, null, null, null);

        assertThat(manager.resolveMcpToolTimeout(server)).isEqualTo(java.time.Duration.ofSeconds(300));
    }

    @Test
    void readResourceUsesConfiguredMcpTimeout() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getTimeouts().getMcp().setToolCall(0.05);
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());
        CountDownLatch release = new CountDownLatch(1);
        McpSyncClient client = mock(McpSyncClient.class);
        doAnswer(invocation -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new McpSchema.ReadResourceResult(List.of());
        }).when(client).readResource(any(McpSchema.ReadResourceRequest.class));
        injectClient(manager, "srv", client, List.of());

        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> manager.readResource("srv", "resource://slow"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MCP read resource failed")
                .hasMessageContaining("MCP call timed out")
                .hasMessageContaining("read_resource");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
        } finally {
            release.countDown();
            manager.closeAll();
        }
    }

    @Test
    void listResourcesForToolUsesConfiguredMcpTimeout() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getTimeouts().getMcp().setToolCall(0.05);
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());
        CountDownLatch release = new CountDownLatch(1);
        McpSyncClient client = mock(McpSyncClient.class);
        doAnswer(invocation -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new McpSchema.ListResourcesResult(List.of(), null);
        }).when(client).listResources();
        injectClient(manager, "srv", client, List.of());

        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> manager.listResourcesForTool("srv"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MCP list resources failed")
                .hasMessageContaining("MCP call timed out")
                .hasMessageContaining("list_resources");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
        } finally {
            release.countDown();
            manager.closeAll();
        }
    }

    @Test
    void listPromptsForToolUsesConfiguredMcpTimeout() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getTimeouts().getMcp().setToolCall(0.05);
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());
        CountDownLatch release = new CountDownLatch(1);
        McpSyncClient client = mock(McpSyncClient.class);
        doAnswer(invocation -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new McpSchema.ListPromptsResult(List.of(), null);
        }).when(client).listPrompts();
        injectClient(manager, "srv", client, List.of());

        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> manager.listPromptsForTool("srv"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MCP list prompts failed")
                .hasMessageContaining("MCP call timed out")
                .hasMessageContaining("list_prompts");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
        } finally {
            release.countDown();
            manager.closeAll();
        }
    }

    @Test
    void getPromptForToolUsesConfiguredMcpTimeout() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getTimeouts().getMcp().setToolCall(0.05);
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());
        CountDownLatch release = new CountDownLatch(1);
        McpSyncClient client = mock(McpSyncClient.class);
        doAnswer(invocation -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new McpSchema.GetPromptResult("done", List.of());
        }).when(client).getPrompt(any(McpSchema.GetPromptRequest.class));
        injectClient(manager, "srv", client, List.of());

        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> manager.getPromptForTool("srv", "{\"name\":\"slow\"}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MCP get prompt failed")
                .hasMessageContaining("MCP call timed out")
                .hasMessageContaining("get_prompt");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
        } finally {
            release.countDown();
            manager.closeAll();
        }
    }

    @Test
    void isReadOnlyHintRequiresExactTrueAnnotation() {
        McpSchema.Tool missing = McpSchema.Tool.builder("missing").inputSchema(Map.of()).build();
        McpSchema.Tool falseHint = McpSchema.Tool.builder("false_hint")
            .inputSchema(Map.of())
            .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false).build())
            .build();
        McpSchema.Tool trueHint = McpSchema.Tool.builder("true_hint")
            .inputSchema(Map.of())
            .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).build())
            .build();

        assertThat(McpLifecycleManager.isReadOnlyHint(missing)).isFalse();
        assertThat(McpLifecycleManager.isReadOnlyHint(falseHint)).isFalse();
        assertThat(McpLifecycleManager.isReadOnlyHint(trueHint)).isTrue();
    }

    @Test
    void refreshTools_doesNothingWhenNoChange() throws Exception {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(registry);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx, new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(), new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()), new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool1 = McpSchema.Tool.builder("tool1").title("t").description("d").inputSchema(Map.of()).build();
        injectClient(manager, "srv", client, List.of(tool1));

        // Same tools returned
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool1), ""));

        manager.refreshTools("srv");

        // Should NOT deregister or register anything
        verify(registry, never()).deregisterDynamic(any());
        verify(registry, never()).registerDynamic(any(), any(), any());
        verify(registry, never()).registerDynamic(any(), any(), any(), any());
    }

    @Test
    void remoteHeaderCustomizerAddsConfiguredHeaders() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.getHeaders().put("X-Api-Key", "secret");

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://mcp.example/mcp"));
        manager.remoteHeaderCustomizer(server)
            .customize(builder, "POST", URI.create("https://mcp.example/mcp"), "{}", null);

        assertThat(builder.build().headers().firstValue("X-Api-Key")).contains("secret");
    }

    @Test
    void resolveRemoteHeadersAddsOauthBearerWhenAuthorizationIsMissing() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        McpOAuthManager oauthManager = mock(McpOAuthManager.class);
        when(oauthManager.getToken("srv")).thenReturn(Optional.of("tok-123"));
        injectOAuthManager(manager, oauthManager);
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.getHeaders().put("X-Tenant", "acme");

        Map<String, String> headers = manager.resolveRemoteHeaders(server);

        assertThat(headers).containsEntry("X-Tenant", "acme");
        assertThat(headers).containsEntry("Authorization", "Bearer tok-123");
    }

    @Test
    void resolveRemoteHeadersKeepsExplicitAuthorizationHeader() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        McpOAuthManager oauthManager = mock(McpOAuthManager.class);
        injectOAuthManager(manager, oauthManager);
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.getHeaders().put("authorization", "Bearer explicit");

        Map<String, String> headers = manager.resolveRemoteHeaders(server);

        assertThat(headers).containsEntry("authorization", "Bearer explicit");
        assertThat(headers).doesNotContainEntry("Authorization", "Bearer tok-123");
        verify(oauthManager, never()).getToken("srv");
    }

    @Test
    void refreshTools_handlesMissingServerGracefully() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        // Should not throw
        manager.refreshTools("nonexistent");
    }

    @Test
    void reconnect_closesExistingAndSchedulesReconnect() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setTransport("stdio");
        server.setCommand("/nonexistent-binary-xyz");
        properties.getMcp().getServers().add(server);

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(mock(ToolRegistry.class));

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx, new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(), new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()), new SlidingWindowRateLimiter());

        // Inject existing client
        McpSyncClient client = mock(McpSyncClient.class);
        injectClient(manager, "srv", client, List.of());

        manager.reconnect("srv");

        // Existing client should be closed
        verify(client).close();
        // Server should be removed from clients map
        assertThat(manager.listServers()).isEmpty();
    }

    @Test
    void reconnect_unknownServerLogsWarning() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        // Should not throw
        manager.reconnect("nonexistent");
    }

    @Test
    void mcpToolHandlerStripsCredentialsFromError() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null,
            new McpToolDefinitionScanner(new ObjectMapper()), new McpResponseScanner(),
            new ToolArgumentInjectionScanner(), new ToolFingerprintStore(new ObjectMapper()),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("Bearer sk-secret123456 failed"));
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult toolResult = handler.execute("{}", null, null);

        assertThat(toolResult.success()).isFalse();
        assertThat(toolResult.error()).contains("[REDACTED]");
        assertThat(toolResult.error()).doesNotContain("sk-secret123456");
        assertJsonError(toolResult).contains("[REDACTED]");
    }

    @Test
    void executeToolStripsCredentialsFromError() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("key=supersecret leaked"));
        injectClient(manager, "srv", client, List.of());

        try {
            manager.executeTool("srv", "tool", "{}");
            org.assertj.core.api.Assertions.fail("Expected exception");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("[REDACTED]");
            assertThat(e.getMessage()).doesNotContain("supersecret");
        }
    }

    @Test
    void executeToolRefreshesOauthTokenAndRetriesOnceOnAuthError() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        McpOAuthManager oauthManager = mock(McpOAuthManager.class);
        injectOAuthManager(manager, oauthManager);

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult success = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "ok")), false, null, null);
        when(client.callTool(any()))
            .thenThrow(new RuntimeException("401 Unauthorized Bearer sk-old-token"))
            .thenReturn(success);
        injectClient(manager, "srv", client, List.of());

        McpSchema.CallToolResult result = manager.executeTool("srv", "tool", "{}");

        assertThat(result.content()).hasSize(1);
        verify(oauthManager).refreshToken("srv");
        verify(client, times(2)).callTool(any());
    }

    @Test
    void executeToolReportsNeedsReauthWhenOauthRecoveryFails() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);
        McpOAuthManager oauthManager = mock(McpOAuthManager.class);
        doThrow(new java.io.IOException("refresh failed token=supersecret"))
            .when(oauthManager).refreshToken("srv");
        injectOAuthManager(manager, oauthManager);

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("401 Unauthorized Bearer sk-old-token"));
        injectClient(manager, "srv", client, List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.executeTool("srv", "tool", "{}"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("needs_reauth=true")
            .hasMessageContaining("[REDACTED]")
            .hasMessageNotContaining("supersecret")
            .hasMessageNotContaining("sk-old-token");
    }

    @Test
    void readResourceStripsCredentialsFromError() throws Exception {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null, null, null, null, null, null);

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.readResource(any(McpSchema.ReadResourceRequest.class))).thenThrow(new RuntimeException("password=hunter2 error"));
        injectClient(manager, "srv", client, List.of());

        try {
            manager.readResource("srv", "resource://x");
            org.assertj.core.api.Assertions.fail("Expected exception");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("[REDACTED]");
            assertThat(e.getMessage()).doesNotContain("hunter2");
        }
    }

    @SuppressWarnings("unchecked")
    private void injectClient(McpLifecycleManager manager, String name, McpSyncClient client, List<McpSchema.Tool> tools) throws Exception {
        injectClient(manager, name, client, tools, null);
    }

    @SuppressWarnings("unchecked")
    private void injectClient(McpLifecycleManager manager, String name, McpSyncClient client,
                              List<McpSchema.Tool> tools, String trust) throws Exception {
        injectClient(manager, name, client, tools, trust, null, null);
    }

    @SuppressWarnings("unchecked")
    private void injectClient(McpLifecycleManager manager, String name, McpSyncClient client,
                              List<McpSchema.Tool> tools, String trust,
                              List<String> include, List<String> exclude) throws Exception {
        Field field = McpLifecycleManager.class.getDeclaredField("clients");
        field.setAccessible(true);
        ConcurrentHashMap<String, Object> map = (ConcurrentHashMap<String, Object>) field.get(manager);
        Class<?> stateClass = Class.forName("com.azhukov.agent.client.mcp.McpLifecycleManager$McpServerState");
        AgentProperties.McpProperties.ServerProperties props = new AgentProperties.McpProperties.ServerProperties();
        props.setName(name);
        if (trust != null) {
            props.setTrust(trust);
        }
        props.getTools().setInclude(include);
        props.getTools().setExclude(exclude);
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        map.put(name, ctor.newInstance(props, client, tools));
    }

    private void injectOAuthManager(McpLifecycleManager manager, McpOAuthManager oauthManager) throws Exception {
        Field field = McpLifecycleManager.class.getDeclaredField("mcpOAuthManager");
        field.setAccessible(true);
        field.set(manager, oauthManager);
    }

    private void injectMcpToolTrustService(McpLifecycleManager manager, McpToolTrustService trustService) throws Exception {
        Field field = McpLifecycleManager.class.getDeclaredField("mcpToolTrustService");
        field.setAccessible(true);
        field.set(manager, trustService);
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name)
            .title(name)
            .description(name)
            .inputSchema(Map.of())
            .build();
    }

    private Path mediaPathFromText(String text) {
        int start = text.indexOf("MEDIA:");
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = text.indexOf('\n', start);
        if (end < 0) {
            end = text.length();
        }
        return Path.of(text.substring(start + "MEDIA:".length(), end));
    }

    private Path resourcePathFromRendered(String text) {
        int start = text.indexOf("saved to ");
        assertThat(start).isGreaterThanOrEqualTo(0);
        start += "saved to ".length();
        int end = text.indexOf(" (", start);
        assertThat(end).isGreaterThan(start);
        return Path.of(text.substring(start, end));
    }

    private String assertJsonError(ToolResult result) throws Exception {
        assertThat(result.content()).isNotBlank();
        JsonNode json = new ObjectMapper().readTree(result.content());
        assertThat(json.get("success").asBoolean()).isFalse();
        String error = json.get("error").asText();
        assertThat(result.error()).isEqualTo(error);
        return error;
    }
}
