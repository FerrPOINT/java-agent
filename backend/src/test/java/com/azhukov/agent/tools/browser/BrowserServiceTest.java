package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.security.UrlSafety;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrowserServiceTest {

    private static final String CDP_URL = "http://localhost:9222";
    private static final String EXAMPLE_URL = "https://example.com";
    private static final String ABOUT_BLANK = "about:blank";

    @Mock
    private CdpClient cdpClient;

    @Mock
    private UrlSafety urlSafety;

    private BrowserService browserService() {
        return new BrowserService(cdpClient, () -> CDP_URL, urlSafety);
    }

    private CompletableFuture<JsonNode> future(JsonNode value) {
        return CompletableFuture.completedFuture(value);
    }

    @Test
    void navigateReturnsPageSnapshot() throws Exception {
        BrowserService service = browserService();
        when(urlSafety.isUrlAllowed(EXAMPLE_URL)).thenReturn(true);
        when(cdpClient.isConnected()).thenReturn(false);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode navigateResult = mapper.createObjectNode()
            .put("frameId", "frame-abc-123");
        when(cdpClient.send(eq("Page.navigate"), any(ObjectNode.class))).thenReturn(future(navigateResult));

        String result = service.navigate(EXAMPLE_URL);

        assertThat(result)
            .contains("Navigated to " + EXAMPLE_URL)
            .contains("frameId=frame-abc-123");
        verify(cdpClient).connect(CDP_URL);
        verify(cdpClient).send(eq("Page.navigate"), any(ObjectNode.class));
    }

    @Test
    void navigateReportsErrorText() throws Exception {
        BrowserService service = browserService();
        when(urlSafety.isUrlAllowed(EXAMPLE_URL)).thenReturn(true);
        when(cdpClient.isConnected()).thenReturn(true);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode navigateResult = mapper.createObjectNode()
            .put("frameId", "frame-err")
            .put("errorText", "net::ERR_NAME_NOT_RESOLVED");
        when(cdpClient.send(eq("Page.navigate"), any(ObjectNode.class))).thenReturn(future(navigateResult));

        String result = service.navigate(EXAMPLE_URL);

        assertThat(result).isEqualTo("Navigation error: net::ERR_NAME_NOT_RESOLVED");
    }

    @Test
    void navigateBlocksUnsafeUrl() throws Exception {
        BrowserService service = browserService();
        when(urlSafety.isUrlAllowed("https://blocked.example")).thenReturn(false);

        String result = service.navigate("https://blocked.example");

        assertThat(result).isEqualTo("URL blocked by safety policy: https://blocked.example");
        verify(cdpClient, never()).connect(anyString());
        verify(cdpClient, never()).send(anyString(), any());
    }

    @Test
    void screenshotReturnsBase64Png() throws Exception {
        BrowserService service = browserService();
        when(cdpClient.isConnected()).thenReturn(true);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode screenshotResult = mapper.createObjectNode().put("data", "aGVsbG8=");
        when(cdpClient.send(eq("Page.captureScreenshot"), any(ObjectNode.class))).thenReturn(future(screenshotResult));

        String result = service.screenshot();

        assertThat(result).isEqualTo("data:image/png;base64,aGVsbG8=");
    }

    @Test
    void evaluateReturnsValue() throws Exception {
        BrowserService service = browserService();
        when(cdpClient.isConnected()).thenReturn(true);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode evalResult = mapper.createObjectNode()
            .set("result", mapper.createObjectNode().put("value", "Hello from page"));
        when(cdpClient.send(eq("Runtime.evaluate"), any(ObjectNode.class))).thenReturn(future(evalResult));

        String result = service.evaluate("document.title");

        assertThat(result).isEqualTo("Hello from page");
    }
}
