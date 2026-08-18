package com.azhukov.agent.tools.browser;

import com.azhukov.agent.security.UrlSafety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

@Service
public class BrowserService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CdpClient cdpClient;
    private final Supplier<String> cdpUrlSupplier;
    private final UrlSafety urlSafety;

    @Autowired
    public BrowserService(CdpClient cdpClient, ChromiumAutoStart chromiumAutoStart, UrlSafety urlSafety) {
        this.cdpClient = cdpClient;
        this.urlSafety = urlSafety;
        this.cdpUrlSupplier = chromiumAutoStart::getCdpUrl;
    }

    BrowserService(CdpClient cdpClient, Supplier<String> cdpUrlSupplier, UrlSafety urlSafety) {
        this.cdpClient = cdpClient;
        this.cdpUrlSupplier = cdpUrlSupplier;
        this.urlSafety = urlSafety;
    }

    private String cdpUrl() {
        return cdpUrlSupplier.get();
    }

    public String navigate(String url) {
        return navigate(url, 30);
    }

    public String navigate(String url, int waitSeconds) {
        if (!urlSafety.isUrlAllowed(url)) {
            return "URL blocked by safety policy: " + url;
        }
        try {
            ensureConnected();
        } catch (Exception e) {
            return "Navigation error: " + e.getMessage();
        }
        ObjectNode params = MAPPER.createObjectNode();
        params.put("url", url);
        try {
            JsonNode result = cdpClient.send("Page.navigate", params).get(120, TimeUnit.SECONDS);
            JsonNode frameId = result.get("frameId");
            JsonNode error = result.get("errorText");
            if (error != null && !error.isNull()) {
                return "Navigation error: " + error.asText();
            }
            waitForLoad(waitSeconds);
            return "Navigated to " + url + " (frameId=" + (frameId != null ? frameId.asText() : "?") + ")";
        } catch (Exception e) {
            return "Navigation error: " + e.getMessage();
        }
    }

    /** Handle a JavaScript dialog (alert/confirm/prompt) via CDP Page.handleJavaScriptDialog */
    public String handleDialog(boolean accept, String promptText) {
        try {
            ensureConnected();
        } catch (Exception e) {
            return "Dialog error: " + e.getMessage();
        }
        ObjectNode params = MAPPER.createObjectNode();
        params.put("accept", accept);
        if (promptText != null && !promptText.isBlank()) {
            params.put("promptText", promptText);
        }
        try {
            cdpClient.send("Page.handleJavaScriptDialog", params).get(30, TimeUnit.SECONDS);
            return accept ? "Dialog accepted" : "Dialog dismissed";
        } catch (Exception e) {
            return "Dialog error: " + e.getMessage();
        }
    }

    /** Get a full accessibility tree snapshot via CDP Accessibility.getFullAXTree */
    public String accessibilitySnapshot(boolean full) throws Exception {
        ensureConnected();
        ObjectNode params = MAPPER.createObjectNode();
        JsonNode result = cdpClient.send("Accessibility.getFullAXTree", params).get(60, TimeUnit.SECONDS);
        JsonNode nodes = result.path("nodes");
        if (nodes.isMissingNode() || !nodes.isArray()) {
            return "Snapshot failed: no accessibility tree";
        }
        StringBuilder sb = new StringBuilder();
        int maxNodes = full ? 500 : 80;
        int count = 0;
        for (JsonNode node : nodes) {
            if (count >= maxNodes) {
                sb.append("... (truncated, ").append(nodes.size()).append(" total nodes)\n");
                break;
            }
            String role = node.path("role").path("value").asText("");
            String name = node.path("name").path("value").asText("");
            String ignored = node.path("ignored").asBoolean(false) ? " [ignored]" : "";
            // Only include meaningful nodes in compact mode
            if (!full && (role.isEmpty() || role.equals("generic") || role.equals("None"))) {
                continue;
            }
            if (!name.isBlank() || !role.isBlank()) {
                sb.append("[").append(role).append("] ");
                if (!name.isBlank()) {
                    sb.append(name);
                }
                sb.append(ignored).append("\n");
                count++;
            }
        }
        return sb.toString();
    }

    public String click(String selector) throws Exception {
        ensureConnected();
        JsonNode document = cdpClient.send("DOM.getDocument", null).get(60, TimeUnit.SECONDS);
        int rootNodeId = document.path("root").path("nodeId").asInt();

        ObjectNode params = MAPPER.createObjectNode();
        params.put("nodeId", rootNodeId);
        params.put("selector", selector);
        JsonNode result = cdpClient.send("DOM.querySelector", params).get(60, TimeUnit.SECONDS);
        JsonNode nodeId = result.get("nodeId");
        if (nodeId == null || nodeId.asInt() == 0) {
            return "Element not found: " + selector;
        }

        String safeSelector = MAPPER.writeValueAsString(selector);
        String expression = "document.querySelector(" + safeSelector + ")?.click()";
        return evaluate(expression);
    }

    public String screenshot() throws Exception {
        ensureConnected();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("format", "png");
        JsonNode result = cdpClient.send("Page.captureScreenshot", params).get(120, TimeUnit.SECONDS);
        JsonNode data = result.get("data");
        if (data == null) {
            return "Screenshot failed: no data";
        }
        return "data:image/png;base64," + data.asText();
    }

    public String evaluate(String expression) throws Exception {
        ensureConnected();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        JsonNode result = cdpClient.send("Runtime.evaluate", params).get(60, TimeUnit.SECONDS);
        JsonNode value = result.path("result").path("value");
        return value.isMissingNode() ? result.toString() : value.asText();
    }

    private void ensureConnected() throws Exception {
        if (!cdpClient.isConnected()) {
            try {
                // Always re-discover the WebSocket URL from the HTTP endpoint — the browser
                // may have been restarted, making the old webSocketUrl stale (BUG 2).
                cdpClient.connect(cdpUrl());
            } catch (Exception e) {
                // If connect fails and we had a previous connection, try reconnect as a fallback.
                // reconnect() reuses the last known WebSocket URL, which may still be valid
                // if only the HTTP endpoint was temporarily unavailable.
                if (cdpClient.isConnected()) {
                    cdpClient.reconnect();
                } else {
                    throw e;
                }
            }
        }
    }

    private void waitForLoad() throws Exception {
        waitForLoad(30);
    }

    private void waitForLoad(int timeoutSeconds) throws Exception {
        cdpClient.waitForEvent("Page.loadEventFired", timeoutSeconds).get(timeoutSeconds, TimeUnit.SECONDS);
    }
}