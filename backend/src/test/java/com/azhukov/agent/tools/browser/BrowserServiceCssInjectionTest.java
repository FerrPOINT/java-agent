package com.azhukov.agent.tools.browser;

import com.azhukov.agent.security.UrlSafety;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for BrowserService CSS selector injection fix.
 * Verifies that the selector is safely escaped via JSON.stringify
 * instead of being concatenated directly into a JS expression.
 */
class BrowserServiceCssInjectionTest {

    @Test
    void clickWithMaliciousSelectorDoesNotInjectJs() throws Exception {
        CdpClient cdp = mock(CdpClient.class);
        when(cdp.isConnected()).thenReturn(true);

        // DOM.getDocument returns a root node
        ObjectNode docResult = new ObjectMapper().createObjectNode();
        ObjectNode root = docResult.putObject("root");
        root.put("nodeId", 1);
        when(cdp.send("DOM.getDocument", null))
            .thenReturn(CompletableFuture.completedFuture((JsonNode) docResult));

        // DOM.querySelector returns a valid nodeId
        ObjectNode queryResult = new ObjectMapper().createObjectNode();
        queryResult.put("nodeId", 42);
        when(cdp.send(eq("DOM.querySelector"), any()))
            .thenReturn(CompletableFuture.completedFuture((JsonNode) queryResult));

        // Runtime.evaluate should receive a safely-quoted selector
        ObjectNode evalResult = new ObjectMapper().createObjectNode();
        evalResult.putObject("result").put("value", "clicked");
        when(cdp.send(eq("Runtime.evaluate"), any()))
            .thenReturn(CompletableFuture.completedFuture((JsonNode) evalResult));

        UrlSafety safety = mock(UrlSafety.class);
        BrowserService service = new BrowserService(cdp, () -> "http://localhost:9222", safety);

        // Malicious selector with single quotes and JS injection attempt
        String maliciousSelector = "'); alert('xss'); document.querySelector('a";
        String result = service.click(maliciousSelector);

        assertThat(result).isEqualTo("clicked");

        // Verify that the Runtime.evaluate call used JSON.stringify (safe quoting)
        // The expression should use JSON.stringify(selector) which properly escapes quotes
        org.mockito.ArgumentCaptor<ObjectNode> captor =
            org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        verify(cdp).send(eq("Runtime.evaluate"), captor.capture());
        String expression = captor.getValue().get("expression").asText();

        // The expression should use JSON.stringify output (properly quoted string)
        // The key fix: the selector is passed through JSON serialization (writeValueAsString)
        // which wraps it in double quotes, rather than using single-quote concatenation.
        // The old code: "document.querySelector('" + selector.replace("'", "\\'") + "')?.click()"
        // The new code: "document.querySelector(" + writeValueAsString(selector) + ")?.click()"
        // writeValueAsString wraps the selector in double quotes, properly escaping content.
        assertThat(expression)
            .as("Expression should use JSON.stringify (writeValueAsString) for safe selector escaping")
            .startsWith("document.querySelector(\"") // Starts with double-quoted string
            .contains(")?.click()"); // Ends with click call
    }

    @Test
    void clickWithNormalSelectorWorks() throws Exception {
        CdpClient cdp = mock(CdpClient.class);
        when(cdp.isConnected()).thenReturn(true);

        ObjectNode docResult = new ObjectMapper().createObjectNode();
        ObjectNode root = docResult.putObject("root");
        root.put("nodeId", 1);
        when(cdp.send("DOM.getDocument", null))
            .thenReturn(CompletableFuture.completedFuture((JsonNode) docResult));

        ObjectNode queryResult = new ObjectMapper().createObjectNode();
        queryResult.put("nodeId", 42);
        when(cdp.send(eq("DOM.querySelector"), any()))
            .thenReturn(CompletableFuture.completedFuture((JsonNode) queryResult));

        ObjectNode evalResult = new ObjectMapper().createObjectNode();
        evalResult.putObject("result").put("value", "clicked");
        when(cdp.send(eq("Runtime.evaluate"), any()))
            .thenReturn(CompletableFuture.completedFuture((JsonNode) evalResult));

        UrlSafety safety = mock(UrlSafety.class);
        BrowserService service = new BrowserService(cdp, () -> "http://localhost:9222", safety);

        String result = service.click("#submit-btn");
        assertThat(result).isEqualTo("clicked");
    }

    @Test
    void clickElementNotFoundReturnsMessage() throws Exception {
        CdpClient cdp = mock(CdpClient.class);
        when(cdp.isConnected()).thenReturn(true);

        ObjectNode docResult = new ObjectMapper().createObjectNode();
        ObjectNode root = docResult.putObject("root");
        root.put("nodeId", 1);
        when(cdp.send("DOM.getDocument", null))
            .thenReturn(CompletableFuture.completedFuture((JsonNode) docResult));

        // nodeId = 0 means element not found
        ObjectNode queryResult = new ObjectMapper().createObjectNode();
        queryResult.put("nodeId", 0);
        when(cdp.send(eq("DOM.querySelector"), any()))
            .thenReturn(CompletableFuture.completedFuture((JsonNode) queryResult));

        UrlSafety safety = mock(UrlSafety.class);
        BrowserService service = new BrowserService(cdp, () -> "http://localhost:9222", safety);

        String result = service.click(".nonexistent");
        assertThat(result).contains("Element not found");
    }
}