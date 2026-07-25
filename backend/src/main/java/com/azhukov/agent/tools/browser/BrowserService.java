package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.security.UrlSafety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

@Service
public class BrowserService {

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
        if (!urlSafety.isUrlAllowed(url)) {
            return "URL blocked by safety policy: " + url;
        }
        try {
            ensureConnected();
        } catch (Exception e) {
            return "Navigation error: " + e.getMessage();
        }
        ObjectNode params = new ObjectMapper().createObjectNode();
        params.put("url", url);
        try {
            JsonNode result = cdpClient.send("Page.navigate", params).get(120, TimeUnit.SECONDS);
            JsonNode frameId = result.get("frameId");
            JsonNode error = result.get("errorText");
            if (error != null && !error.isNull()) {
                return "Navigation error: " + error.asText();
            }
            waitForLoad();
            return "Navigated to " + url + " (frameId=" + (frameId != null ? frameId.asText() : "?") + ")";
        } catch (Exception e) {
            return "Navigation error: " + e.getMessage();
        }
    }

    public String click(String selector) throws Exception {
        ensureConnected();
        String expression = "document.querySelector('" + selector.replace("'", "\\'") + "')?.click()";
        JsonNode document = cdpClient.send("DOM.getDocument", null).get(60, TimeUnit.SECONDS);
        int rootNodeId = document.path("root").path("nodeId").asInt();

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode params = mapper.createObjectNode();
        params.put("nodeId", rootNodeId);
        params.put("selector", selector);
        JsonNode result = cdpClient.send("DOM.querySelector", params).get(60, TimeUnit.SECONDS);
        JsonNode nodeId = result.get("nodeId");
        if (nodeId == null || nodeId.asInt() == 0) {
            return "Element not found: " + selector;
        }
        return evaluate(expression);
    }

    public String screenshot() throws Exception {
        ensureConnected();
        ObjectNode params = new ObjectMapper().createObjectNode();
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
        ObjectNode params = new ObjectMapper().createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        JsonNode result = cdpClient.send("Runtime.evaluate", params).get(60, TimeUnit.SECONDS);
        JsonNode value = result.path("result").path("value");
        return value.isMissingNode() ? result.toString() : value.asText();
    }

    private void ensureConnected() throws Exception {
        if (!cdpClient.isConnected()) {
            cdpClient.connect(cdpUrl());
        }
    }

    private void waitForLoad() throws Exception {
        Thread.sleep(2000);
    }
}
