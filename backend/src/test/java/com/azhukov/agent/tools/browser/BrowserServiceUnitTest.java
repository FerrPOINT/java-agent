package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.tools.web.WebsitePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BrowserServiceUnitTest {

    @Test
    void navigateBlockedBySafety() {
        CdpClient client = mock(CdpClient.class);
        UrlSafety safety = mock(UrlSafety.class);
        when(safety.isUrlAllowed(anyString())).thenReturn(false);

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", safety);
        assertThat(service.navigate("http://bad")).contains("blocked by safety policy");
    }

    @Test
    void navigateBlockedByWebsitePolicyDoesNotConnect() throws Exception {
        CdpClient client = mock(CdpClient.class);
        UrlSafety safety = mock(UrlSafety.class);
        when(safety.isUrlAllowed(anyString())).thenReturn(true);
        AgentProperties properties = new AgentProperties();
        properties.getWeb().getBlockedDomains().add("blocked.example");

        BrowserService service = new BrowserService(
            client,
            () -> "http://localhost:9222",
            safety,
            new WebsitePolicy(properties));

        assertThat(service.navigate("https://sub.blocked.example/page"))
            .contains("Blocked by website policy");
        verify(client, never()).connect(anyString());
        verify(client, never()).send(anyString(), any());
    }

    @Test
    void navigateFailsClosedWhenCloudProviderUnsupported() throws Exception {
        CdpClient client = mock(CdpClient.class);
        UrlSafety safety = mock(UrlSafety.class);
        when(safety.isUrlAllowed(anyString())).thenReturn(true);
        AgentProperties properties = new AgentProperties();
        properties.getBrowser().setCloudProvider("browser-use");

        BrowserService service = new BrowserService(
            client,
            () -> "http://localhost:9222",
            safety,
            null,
            properties);

        assertThat(service.navigate("https://example.com"))
            .contains("Navigation error")
            .contains("Browser provider 'browser-use' is configured")
            .contains("supports only local CDP browser mode");
        verify(client, never()).connect(anyString());
        verify(client, never()).send(anyString(), any());
    }

    @Test
    void browserBackendKeyDoesNotSelectCloudProvider() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectNode result = new ObjectMapper().createObjectNode().put("frameId", "abc123");
        when(client.send(eq("Page.navigate"), any())).thenReturn(CompletableFuture.completedFuture(result));
        when(client.waitForEvent(eq("Page.loadEventFired"), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(new ObjectMapper().createObjectNode()));
        UrlSafety safety = mock(UrlSafety.class);
        when(safety.isUrlAllowed(anyString())).thenReturn(true);
        AgentProperties properties = new AgentProperties();
        properties.getBrowser().setBackend("browser-use");

        BrowserService service = new BrowserService(
            client,
            () -> "http://localhost:9222",
            safety,
            null,
            properties);

        assertThat(service.navigate("https://example.com"))
            .contains("Navigated to https://example.com")
            .contains("abc123");
        verify(client).send(eq("Page.navigate"), any());
    }

    @Test
    void navigateHandlesConnectionError() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(false);
        doThrow(new RuntimeException("connection refused")).when(client).connect(anyString());

        UrlSafety safety = mock(UrlSafety.class);
        when(safety.isUrlAllowed(anyString())).thenReturn(true);

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", safety);
        // BUG 2 fix: when connect() fails and we're not connected, the original exception
        // is re-thrown (no stale reconnect attempt). The error message from connect() is surfaced.
        assertThat(service.navigate("http://example.com")).contains("connection refused");
    }

    @Test
    void navigateReturnsFrameId() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectNode result = new ObjectMapper().createObjectNode().put("frameId", "abc123");
        when(client.send(eq("Page.navigate"), any())).thenReturn(CompletableFuture.completedFuture(result));
        when(client.waitForEvent(eq("Page.loadEventFired"), anyInt())).thenReturn(CompletableFuture.completedFuture(new ObjectMapper().createObjectNode()));

        UrlSafety safety = mock(UrlSafety.class);
        when(safety.isUrlAllowed(anyString())).thenReturn(true);

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", safety);
        assertThat(service.navigate("http://example.com")).contains("Navigated to http://example.com").contains("abc123");
    }

    @Test
    void navigateReturnsErrorText() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectNode result = new ObjectMapper().createObjectNode().put("errorText", "ERR_BLOCKED_BY_CLIENT");
        when(client.send(eq("Page.navigate"), any())).thenReturn(CompletableFuture.completedFuture(result));

        UrlSafety safety = mock(UrlSafety.class);
        when(safety.isUrlAllowed(anyString())).thenReturn(true);

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", safety);
        assertThat(service.navigate("http://example.com")).contains("ERR_BLOCKED_BY_CLIENT");
    }

    @Test
    void clickElementNotFound() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectNode document = new ObjectMapper().createObjectNode();
        document.set("root", new ObjectMapper().createObjectNode().put("nodeId", 1));
        when(client.send(eq("DOM.getDocument"), isNull())).thenReturn(CompletableFuture.completedFuture(document));
        ObjectNode query = new ObjectMapper().createObjectNode().put("nodeId", 0);
        when(client.send(eq("DOM.querySelector"), any())).thenReturn(CompletableFuture.completedFuture(query));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));
        assertThat(service.click("#missing")).contains("Element not found");
    }

    @Test
    void snapshotAssignsRefsToInteractiveBackendNodes() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode result = mapper.createObjectNode();
        ArrayNode nodes = result.putArray("nodes");
        axNode(mapper, nodes, "button", "Save", 101, false);
        axNode(mapper, nodes, "heading", "Settings", 0, false);
        when(client.send(eq("Accessibility.getFullAXTree"), any()))
            .thenReturn(CompletableFuture.completedFuture(result));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));

        String snapshot = service.accessibilitySnapshot(false);

        assertThat(snapshot).contains("button [ref=e1]: Save");
        assertThat(snapshot).contains("[heading] Settings");
    }

    @Test
    void clickRefUsesBackendNodeFromLatestSnapshot() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode snapshotResult = mapper.createObjectNode();
        axNode(mapper, snapshotResult.putArray("nodes"), "button", "Save", 101, false);
        when(client.send(eq("Accessibility.getFullAXTree"), any()))
            .thenReturn(CompletableFuture.completedFuture(snapshotResult));
        ObjectNode resolveResult = mapper.createObjectNode();
        resolveResult.putObject("object").put("objectId", "object-101");
        when(client.send(eq("DOM.resolveNode"), any()))
            .thenReturn(CompletableFuture.completedFuture(resolveResult));
        ObjectNode callResult = mapper.createObjectNode();
        callResult.putObject("result").put("value", "clicked");
        when(client.send(eq("Runtime.callFunctionOn"), any()))
            .thenReturn(CompletableFuture.completedFuture(callResult));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));
        service.accessibilitySnapshot(false);

        assertThat(service.click("@e1")).isEqualTo("clicked");
        org.mockito.ArgumentCaptor<ObjectNode> resolveCaptor =
            org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        verify(client).send(eq("DOM.resolveNode"), resolveCaptor.capture());
        assertThat(resolveCaptor.getValue().path("backendNodeId").asInt()).isEqualTo(101);
        org.mockito.ArgumentCaptor<ObjectNode> callCaptor =
            org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        verify(client).send(eq("Runtime.callFunctionOn"), callCaptor.capture());
        assertThat(callCaptor.getValue().path("functionDeclaration").asText()).contains("this.click()");
        verify(client, never()).send(eq("DOM.querySelector"), any());
    }

    @Test
    void typeRefUsesBackendNodeFromLatestSnapshot() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode snapshotResult = mapper.createObjectNode();
        axNode(mapper, snapshotResult.putArray("nodes"), "textbox", "Search", 202, false);
        when(client.send(eq("Accessibility.getFullAXTree"), any()))
            .thenReturn(CompletableFuture.completedFuture(snapshotResult));
        ObjectNode resolveResult = mapper.createObjectNode();
        resolveResult.putObject("object").put("objectId", "object-202");
        when(client.send(eq("DOM.resolveNode"), any()))
            .thenReturn(CompletableFuture.completedFuture(resolveResult));
        ObjectNode callResult = mapper.createObjectNode();
        callResult.putObject("result").put("value", "typed");
        when(client.send(eq("Runtime.callFunctionOn"), any()))
            .thenReturn(CompletableFuture.completedFuture(callResult));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));
        service.accessibilitySnapshot(false);

        assertThat(service.type("@e1", "hello", true)).isEqualTo("typed");
        org.mockito.ArgumentCaptor<ObjectNode> callCaptor =
            org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        verify(client).send(eq("Runtime.callFunctionOn"), callCaptor.capture());
        ObjectNode callParams = callCaptor.getValue();
        assertThat(callParams.path("objectId").asText()).isEqualTo("object-202");
        assertThat(callParams.path("arguments").get(0).path("value").asText()).isEqualTo("hello");
        assertThat(callParams.path("arguments").get(1).path("value").asBoolean()).isTrue();
    }

    @Test
    void screenshotReturnsBase64() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectNode result = new ObjectMapper().createObjectNode().put("data", "iVBORw0KGgo=");
        when(client.send(eq("Page.captureScreenshot"), any())).thenReturn(CompletableFuture.completedFuture(result));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));
        assertThat(service.screenshot()).startsWith("data:image/png;base64,");
    }

    @Test
    void captureScreenshotWritesFileAndReturnsMediaMetadata(@TempDir Path home) throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        byte[] png = new byte[] {1, 2, 3};
        String data = Base64.getEncoder().encodeToString(png);
        ObjectNode result = new ObjectMapper().createObjectNode().put("data", data);
        when(client.send(eq("Page.captureScreenshot"), any())).thenReturn(CompletableFuture.completedFuture(result));

        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));

            BrowserService.BrowserScreenshot screenshot = service.captureScreenshot();

            assertThat(screenshot.success()).isTrue();
            assertThat(screenshot.dataUrl()).isEqualTo("data:image/png;base64," + data);
            assertThat(screenshot.screenshotPath()).contains(".hermes");
            assertThat(screenshot.mediaTag()).isEqualTo("MEDIA:" + screenshot.screenshotPath());
            assertThat(screenshot.mimeType()).isEqualTo("image/png");
            Path path = Path.of(screenshot.screenshotPath());
            try {
                assertThat(path).startsWith(home.resolve(".hermes").resolve("cache").resolve("screenshots"));
                assertThat(path.getFileName().toString()).startsWith("browser_screenshot_").endsWith(".png");
                assertThat(Files.readAllBytes(path)).isEqualTo(png);
            } finally {
                Files.deleteIfExists(path);
            }
        } finally {
            if (oldHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", oldHome);
            }
        }
    }

    @Test
    void screenshotReportsFailure() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        when(client.send(eq("Page.captureScreenshot"), any())).thenReturn(CompletableFuture.completedFuture(new ObjectMapper().createObjectNode()));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));
        assertThat(service.screenshot()).contains("Screenshot failed");
    }

    @Test
    void evaluateReturnsJsonForArrayValues() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode image = mapper.createObjectNode().put("src", "https://example.com/logo.png").put("alt", "Logo");
        ObjectNode remote = mapper.createObjectNode();
        remote.set("value", mapper.createArrayNode().add(image));
        ObjectNode result = mapper.createObjectNode();
        result.set("result", remote);
        when(client.send(eq("Runtime.evaluate"), any())).thenReturn(CompletableFuture.completedFuture(result));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));

        assertThat(service.evaluate("Array.from(document.images)"))
            .isEqualTo("[{\"src\":\"https://example.com/logo.png\",\"alt\":\"Logo\"}]");
    }

    @Test
    void evaluateReportsExceptionDetails() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode exception = mapper.createObjectNode();
        exception.put("text", "Uncaught");
        exception.putObject("exception").put("description", "SyntaxError: Illegal return statement");
        ObjectNode result = mapper.createObjectNode();
        result.set("exceptionDetails", exception);
        when(client.send(eq("Runtime.evaluate"), any())).thenReturn(CompletableFuture.completedFuture(result));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));

        assertThat(service.evaluate("return 1")).contains("Evaluation error").contains("Illegal return statement");
    }

    @Test
    void pressDispatchesKeyDownAndKeyUp() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        when(client.send(eq("Input.dispatchKeyEvent"), any()))
            .thenReturn(CompletableFuture.completedFuture(new ObjectMapper().createObjectNode()));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));

        assertThat(service.press("enter")).isEqualTo("Pressed Enter");
        org.mockito.ArgumentCaptor<ObjectNode> captor =
            org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        verify(client, times(2)).send(eq("Input.dispatchKeyEvent"), captor.capture());
        assertThat(captor.getAllValues().get(0).path("type").asText()).isEqualTo("keyDown");
        assertThat(captor.getAllValues().get(1).path("type").asText()).isEqualTo("keyUp");
        assertThat(captor.getAllValues().get(0).path("key").asText()).isEqualTo("Enter");
        assertThat(captor.getAllValues().get(0).path("windowsVirtualKeyCode").asInt()).isEqualTo(13);
    }

    @Test
    void evaluateReturnsValue() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectNode result = new ObjectMapper().createObjectNode();
        result.set("result", new ObjectMapper().createObjectNode().put("value", "42"));
        when(client.send(eq("Runtime.evaluate"), any())).thenReturn(CompletableFuture.completedFuture(result));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));
        assertThat(service.evaluate("1+1")).isEqualTo("42");
    }

    @Test
    void evaluateBlocksPrivateUrlLiteralBeforeCdpLikeHermes() throws Exception {
        CdpClient client = mock(CdpClient.class);
        UrlSafety safety = mock(UrlSafety.class);
        String privateUrl = "http://127.0.0.1/admin";
        when(safety.isUrlAllowed(privateUrl)).thenReturn(false);

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", safety);

        assertThatThrownBy(() -> service.evaluate("fetch('" + privateUrl + "')"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JavaScript expression targets a blocked URL")
            .hasMessageContaining(privateUrl);
        verify(client, never()).connect(anyString());
        verify(client, never()).send(anyString(), any());
    }

    @Test
    void rawCdpSendsMethodAndObjectParams() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode commandResult = mapper.createObjectNode();
        commandResult.putArray("targetInfos").addObject().put("targetId", "tab-1");
        when(client.send(eq("Target.getTargets"), any()))
            .thenReturn(CompletableFuture.completedFuture(commandResult));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));
        ObjectNode params = mapper.createObjectNode().put("filter", "page");

        String result = service.rawCdp("Target.getTargets", params, 5);

        assertThat(result).contains("\"success\":true");
        assertThat(result).contains("\"method\":\"Target.getTargets\"");
        assertThat(result).contains("\"targetId\":\"tab-1\"");
        org.mockito.ArgumentCaptor<ObjectNode> captor =
            org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        verify(client).send(eq("Target.getTargets"), captor.capture());
        assertThat(captor.getValue().path("filter").asText()).isEqualTo("page");
    }

    @Test
    void rawCdpPageNavigateBlocksUnsafeUrlBeforeCdpLikeHermes() throws Exception {
        CdpClient client = mock(CdpClient.class);
        UrlSafety safety = mock(UrlSafety.class);
        String metadataUrl = "http://169.254.169.254/latest/meta-data";
        when(safety.isUrlAllowed(metadataUrl)).thenReturn(false);
        ObjectNode params = new ObjectMapper().createObjectNode().put("url", metadataUrl);

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", safety);

        assertThatThrownBy(() -> service.rawCdp("Page.navigate", params, 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("URL blocked by safety policy")
            .hasMessageContaining(metadataUrl);
        verify(client, never()).connect(anyString());
        verify(client, never()).send(anyString(), any());
    }

    @Test
    void rawCdpRejectsNonObjectParams() {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));

        assertThatThrownBy(() -> service.rawCdp("Runtime.evaluate", new ObjectMapper().createArrayNode(), 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("params must be a JSON object");
    }

    @Test
    @SuppressWarnings("unchecked")
    void consoleCollectsRuntimeConsoleExceptionsAndLogEntries() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        when(client.send(eq("Log.enable"), isNull()))
            .thenReturn(CompletableFuture.completedFuture(mapper.createObjectNode()));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));

        service.console(false);

        org.mockito.ArgumentCaptor<String> methodCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Consumer<JsonNode>> listenerCaptor =
            org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(client, times(3)).onEvent(methodCaptor.capture(), listenerCaptor.capture());
        Map<String, Consumer<JsonNode>> listeners = new LinkedHashMap<>();
        for (int i = 0; i < methodCaptor.getAllValues().size(); i++) {
            listeners.put(methodCaptor.getAllValues().get(i), listenerCaptor.getAllValues().get(i));
        }

        ObjectNode consoleEvent = mapper.createObjectNode();
        consoleEvent.put("type", "log");
        consoleEvent.putArray("args")
            .addObject()
            .put("type", "string")
            .put("value", "hello");
        listeners.get("Runtime.consoleAPICalled").accept(consoleEvent);

        ObjectNode exceptionEvent = mapper.createObjectNode();
        exceptionEvent.putObject("exceptionDetails")
            .put("text", "Uncaught")
            .putObject("exception")
            .put("description", "TypeError: boom");
        listeners.get("Runtime.exceptionThrown").accept(exceptionEvent);

        ObjectNode logEvent = mapper.createObjectNode();
        logEvent.putObject("entry")
            .put("level", "error")
            .put("source", "network")
            .put("text", "Failed to load resource");
        listeners.get("Log.entryAdded").accept(logEvent);

        JsonNode output = mapper.readTree(service.console(true));

        assertThat(output.path("success").asBoolean()).isTrue();
        assertThat(output.path("total_messages").asInt()).isEqualTo(2);
        assertThat(output.path("total_errors").asInt()).isEqualTo(1);
        assertThat(output.path("console_messages").get(0).path("text").asText()).isEqualTo("hello");
        assertThat(output.path("console_messages").get(1).path("source").asText()).isEqualTo("network");
        assertThat(output.path("js_errors").get(0).path("message").asText()).isEqualTo("TypeError: boom");

        JsonNode cleared = mapper.readTree(service.console(false));
        assertThat(cleared.path("total_messages").asInt()).isZero();
        assertThat(cleared.path("total_errors").asInt()).isZero();
    }

    private static void axNode(
        ObjectMapper mapper,
        ArrayNode nodes,
        String role,
        String name,
        int backendNodeId,
        boolean ignored
    ) {
        ObjectNode node = mapper.createObjectNode();
        node.putObject("role").put("value", role);
        node.putObject("name").put("value", name);
        node.put("ignored", ignored);
        if (backendNodeId > 0) {
            node.put("backendDOMNodeId", backendNodeId);
        }
        nodes.add(node);
    }
}
