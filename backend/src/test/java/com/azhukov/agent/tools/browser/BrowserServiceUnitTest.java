package com.azhukov.agent.tools.browser;

import com.azhukov.agent.security.UrlSafety;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
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
    void screenshotReturnsBase64() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectNode result = new ObjectMapper().createObjectNode().put("data", "iVBORw0KGgo=");
        when(client.send(eq("Page.captureScreenshot"), any())).thenReturn(CompletableFuture.completedFuture(result));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));
        assertThat(service.screenshot()).startsWith("data:image/png;base64,");
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
    void evaluateReturnsValue() throws Exception {
        CdpClient client = mock(CdpClient.class);
        when(client.isConnected()).thenReturn(true);
        ObjectNode result = new ObjectMapper().createObjectNode();
        result.set("result", new ObjectMapper().createObjectNode().put("value", "42"));
        when(client.send(eq("Runtime.evaluate"), any())).thenReturn(CompletableFuture.completedFuture(result));

        BrowserService service = new BrowserService(client, () -> "http://localhost:9222", mock(UrlSafety.class));
        assertThat(service.evaluate("1+1")).isEqualTo("42");
    }
}
