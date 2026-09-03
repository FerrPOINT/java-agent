package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.McpResponseScanner;
import com.azhukov.agent.core.security.McpToolDefinitionScanner;
import com.azhukov.agent.core.security.SlidingWindowRateLimiter;
import com.azhukov.agent.core.security.ToolArgumentInjectionScanner;
import com.azhukov.agent.core.security.ToolFingerprintStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolHandlerTest {

    @Test
    void executeReturnsOkOnSuccess() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "ok")), false, null, null);
        when(client.callTool(any())).thenReturn(result);
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult toolResult = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(toolResult.success()).isTrue();
        JsonNode json = objectMapper.readTree(toolResult.content());
        assertThat(json.get("result").asText()).isEqualTo("ok");
    }

    @Test
    void executeReturnsStructuredContentAndFilteredMetaOnSuccess() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("modelcontextprotocol.io/cache", "hit");
        meta.put("com.example.mcp/hint", "use-pagination");
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "summary")),
            false,
            Map.of("count", 2),
            meta);
        when(client.callTool(any())).thenReturn(result);
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult toolResult = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(toolResult.success()).isTrue();
        JsonNode json = objectMapper.readTree(toolResult.content());
        assertThat(json.get("result").asText()).isEqualTo("summary");
        assertThat(json.get("structuredContent").get("count").asInt()).isEqualTo(2);
        assertThat(json.get("_meta").has("modelcontextprotocol.io/cache")).isFalse();
        assertThat(json.get("_meta").get("com.example.mcp/hint").asText()).isEqualTo("use-pagination");
    }

    @Test
    void executeRendersNonTextBlocksWithoutRecordDump() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(
                new McpSchema.ImageContent(null, png, "image/png"),
                new McpSchema.ResourceLink("doc.txt", null, "resource://doc", null, "text/plain", null, null, null)),
            false,
            null,
            null);
        when(client.callTool(any())).thenReturn(result);
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult toolResult = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(toolResult.success()).isTrue();
        JsonNode json = objectMapper.readTree(toolResult.content());
        String text = json.get("result").asText();
        assertThat(text)
            .contains("MEDIA:")
            .contains(".png")
            .contains("[MCP resource link: uri=resource://doc, name=doc.txt, mimeType=text/plain")
            .contains("mcp__srv__read_resource");
        Path mediaPath = Path.of(text.substring(text.indexOf("MEDIA:") + "MEDIA:".length(), text.indexOf('\n')));
        try {
            assertThat(Files.exists(mediaPath)).isTrue();
        } finally {
            Files.deleteIfExists(mediaPath);
        }
        assertThat(text)
            .doesNotContain("ImageContent[")
            .doesNotContain("[image data");
    }

    @Test
    void executeReturnsFailWhenProtocolResultIsError() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "remote error")), true, null, null);
        when(client.callTool(any())).thenReturn(result);
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult toolResult = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(toolResult.success()).isFalse();
        assertThat(toolResult.error()).contains("remote error");
        JsonNode json = errorPayload(toolResult);
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("error").asText()).contains("remote error");
        assertThat(toolResult.error()).isEqualTo(json.get("error").asText());
    }

    @Test
    void executeReturnsFailOnException() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("boom"));
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult toolResult = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(toolResult.success()).isFalse();
        assertThat(toolResult.error()).contains("boom");
        JsonNode json = errorPayload(toolResult);
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("error").asText()).contains("boom");
        assertThat(toolResult.error()).isEqualTo(json.get("error").asText());
    }

    @Test
    void executeTimesOutSlowMcpCallUsingGlobalToolCallTimeout() throws Exception {
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
        McpSchema.CallToolResult ok = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "late")), false, null, null);
        doAnswer(invocation -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok;
        }).when(client).callTool(any());
        injectClient(manager, "srv", client, List.of());

        try {
            McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
            long start = System.nanoTime();
            ToolResult toolResult = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(elapsedMs).isLessThan(1000);
            assertThat(toolResult.success()).isFalse();
            assertThat(toolResult.error()).contains("MCP call timed out");
            assertThat(toolResult.error()).contains("configured timeout: 0.1s");
            assertThat(manager.mcpServerErrorCount("srv")).isEqualTo(1);
        } finally {
            release.countDown();
            manager.closeAll();
        }
    }

    @Test
    void executeSanitizesStructuredProtocolErrorPayload() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "Bearer sk-secret123456 failed")), true, null, null);
        when(client.callTool(any())).thenReturn(result);
        injectClient(manager, "srv", client, List.of());

        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");
        ToolResult toolResult = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(toolResult.success()).isFalse();
        JsonNode json = errorPayload(toolResult);
        assertThat(json.get("error").asText()).contains("[REDACTED");
        assertThat(json.get("error").asText()).doesNotContain("sk-secret123456");
        assertThat(toolResult.error()).isEqualTo(json.get("error").asText());
    }

    @Test
    void circuitBreakerShortCircuitsAfterConsecutiveFailures() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("still broken"));
        injectClient(manager, "srv", client, List.of());
        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");

        handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));
        handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));
        handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));
        ToolResult shortCircuited = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(shortCircuited.success()).isFalse();
        assertThat(shortCircuited.error()).contains("unreachable after 3 consecutive failures");
        verify(client, times(3)).callTool(any());
    }

    @Test
    void circuitBreakerHalfOpenProbeSuccessClosesBreaker() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.CallToolResult ok = new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(null, "ok")), false, null, null);
        when(client.callTool(any())).thenReturn(ok);
        injectClient(manager, "srv", client, List.of());
        manager.forceMcpCircuitBreakerForTest("srv", McpLifecycleManager.MCP_CIRCUIT_BREAKER_THRESHOLD,
            McpLifecycleManager.MCP_CIRCUIT_BREAKER_COOLDOWN.plusSeconds(1));

        ToolResult result = manager.new McpToolHandler("srv", "tool")
            .execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(result.success()).isTrue();
        assertThat(manager.mcpServerErrorCount("srv")).isZero();
        verify(client).callTool(any());
    }

    @Test
    void circuitBreakerReopensWhenHalfOpenProbeFails() throws Exception {
        AgentProperties properties = new AgentProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        McpLifecycleManager manager = new McpLifecycleManager(properties, objectMapper, null,
            new McpToolDefinitionScanner(objectMapper),
            new McpResponseScanner(),
            new ToolArgumentInjectionScanner(),
            new ToolFingerprintStore(objectMapper),
            new SlidingWindowRateLimiter());

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new RuntimeException("still broken"));
        injectClient(manager, "srv", client, List.of());
        manager.forceMcpCircuitBreakerForTest("srv", McpLifecycleManager.MCP_CIRCUIT_BREAKER_THRESHOLD,
            McpLifecycleManager.MCP_CIRCUIT_BREAKER_COOLDOWN.plusSeconds(1));
        McpLifecycleManager.McpToolHandler handler = manager.new McpToolHandler("srv", "tool");

        ToolResult probeFailure = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));
        ToolResult shortCircuited = handler.execute("{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(probeFailure.success()).isFalse();
        assertThat(shortCircuited.success()).isFalse();
        assertThat(shortCircuited.error()).contains("unreachable");
        verify(client, times(1)).callTool(any());
    }

    @SuppressWarnings("unchecked")
    private void injectClient(McpLifecycleManager manager, String name, McpSyncClient client, List<McpSchema.Tool> tools) throws Exception {
        Field field = McpLifecycleManager.class.getDeclaredField("clients");
        field.setAccessible(true);
        ConcurrentHashMap<String, Object> map = (ConcurrentHashMap<String, Object>) field.get(manager);
        Class<?> stateClass = Class.forName("com.azhukov.agent.client.mcp.McpLifecycleManager$McpServerState");
        AgentProperties.McpProperties.ServerProperties props = new AgentProperties.McpProperties.ServerProperties();
        props.setName(name);
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        map.put(name, ctor.newInstance(props, client, tools));
    }

    private JsonNode errorPayload(ToolResult result) throws Exception {
        assertThat(result.content()).isNotBlank();
        JsonNode json = new ObjectMapper().readTree(result.content());
        assertThat(json.has("error")).isTrue();
        return json;
    }
}
