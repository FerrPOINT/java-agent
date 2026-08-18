package com.azhukov.agent.tools.browser;

import com.azhukov.agent.security.UrlSafety;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BrowserServiceTest {

    @Test
    void navigateBlockedUrl() {
        CdpClient cdp = mock(CdpClient.class);
        UrlSafety safety = mock(UrlSafety.class);
        when(safety.isUrlAllowed(anyString())).thenReturn(false);
        BrowserService s = new BrowserService(cdp, () -> "http://localhost:9222", safety);
        assertThat(s.navigate("http://evil")).startsWith("URL blocked");
    }

    @Test
    void navigateSucceeds() throws Exception {
        CdpClient cdp = mock(CdpClient.class);
        when(cdp.isConnected()).thenReturn(true);
        ObjectNode res = new ObjectMapper().createObjectNode().put("frameId", "f1");
        when(cdp.send(anyString(), any())).thenReturn(CompletableFuture.completedFuture((JsonNode) res));
        UrlSafety safety = mock(UrlSafety.class);
        when(safety.isUrlAllowed(anyString())).thenReturn(true);
        BrowserService s = new BrowserService(cdp, () -> "http://localhost:9222", safety);
        assertThat(s.navigate("http://example.com")).contains("Navigated to");
    }

    @Test
    void screenshotReturnsDataUri() throws Exception {
        CdpClient cdp = mock(CdpClient.class);
        when(cdp.isConnected()).thenReturn(true);
        ObjectNode res = new ObjectMapper().createObjectNode().put("data", "abc");
        when(cdp.send(anyString(), any())).thenReturn(CompletableFuture.completedFuture((JsonNode) res));
        UrlSafety safety = mock(UrlSafety.class);
        BrowserService s = new BrowserService(cdp, () -> "http://localhost:9222", safety);
        assertThat(s.screenshot()).startsWith("data:image/png;base64,");
    }
}
