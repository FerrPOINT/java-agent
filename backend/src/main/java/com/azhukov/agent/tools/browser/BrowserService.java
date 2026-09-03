package com.azhukov.agent.tools.browser;

import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.tools.web.WebsitePolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BrowserService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> INTERACTIVE_ROLES = Set.of(
        "button",
        "checkbox",
        "combobox",
        "link",
        "menuitem",
        "menuitemcheckbox",
        "menuitemradio",
        "option",
        "radio",
        "searchbox",
        "slider",
        "switch",
        "tab",
        "textbox",
        "treeitem"
    );
    private static final int MAX_CONSOLE_EVENTS = 500;
    private static final Pattern JS_HTTP_URL_LITERAL =
        Pattern.compile("https?://[^\\s'\"`)>\\]}]+", Pattern.CASE_INSENSITIVE);

    private final CdpClient cdpClient;
    private final Supplier<String> cdpUrlSupplier;
    private final UrlSafety urlSafety;
    private final WebsitePolicy websitePolicy;
    private final AgentProperties properties;
    private final AtomicBoolean consoleListenersRegistered = new AtomicBoolean(false);
    private final ConcurrentLinkedDeque<ConsoleMessage> consoleMessages = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<JsError> jsErrors = new ConcurrentLinkedDeque<>();
    private volatile Map<String, Integer> elementRefs = Map.of();

    @Autowired
    public BrowserService(
        CdpClient cdpClient,
        ChromiumAutoStart chromiumAutoStart,
        UrlSafety urlSafety,
        WebsitePolicy websitePolicy,
        AgentProperties properties
    ) {
        this.cdpClient = cdpClient;
        this.urlSafety = urlSafety;
        this.websitePolicy = websitePolicy;
        this.properties = properties;
        this.cdpUrlSupplier = chromiumAutoStart::getCdpUrl;
    }

    BrowserService(CdpClient cdpClient, Supplier<String> cdpUrlSupplier, UrlSafety urlSafety) {
        this(cdpClient, cdpUrlSupplier, urlSafety, null);
    }

    BrowserService(CdpClient cdpClient, Supplier<String> cdpUrlSupplier, UrlSafety urlSafety, WebsitePolicy websitePolicy) {
        this(cdpClient, cdpUrlSupplier, urlSafety, websitePolicy, new AgentProperties());
    }

    BrowserService(CdpClient cdpClient,
                   Supplier<String> cdpUrlSupplier,
                   UrlSafety urlSafety,
                   WebsitePolicy websitePolicy,
                   AgentProperties properties) {
        this.cdpClient = cdpClient;
        this.cdpUrlSupplier = cdpUrlSupplier;
        this.urlSafety = urlSafety;
        this.websitePolicy = websitePolicy;
        this.properties = properties;
    }

    private String cdpUrl() {
        return cdpUrlSupplier.get();
    }

    public String navigate(String url) {
        return navigate(url, 30);
    }

    public String navigate(String url, int waitSeconds) {
        String blockReason = browserUrlBlockReason(url);
        if (blockReason != null) {
            return blockReason;
        }
        try {
            ensureConnected();
        } catch (Exception e) {
            return "Navigation error: " + e.getMessage();
        }
        elementRefs = Map.of();
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
            String redirectBlock = redirectBlockReason(url);
            if (redirectBlock != null) {
                return redirectBlock;
            }
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
            elementRefs = Map.of();
            return "Snapshot failed: no accessibility tree";
        }
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> refs = new LinkedHashMap<>();
        int maxNodes = full ? 500 : 80;
        int count = 0;
        int refIndex = 1;
        for (JsonNode node : nodes) {
            if (count >= maxNodes) {
                sb.append("... (truncated, ").append(nodes.size()).append(" total nodes)\n");
                break;
            }
            String role = node.path("role").path("value").asText("");
            String name = node.path("name").path("value").asText("");
            boolean ignored = node.path("ignored").asBoolean(false);
            // Only include meaningful nodes in compact mode
            if (!full && (role.isEmpty() || role.equals("generic") || role.equals("None"))) {
                continue;
            }
            if (!name.isBlank() || !role.isBlank()) {
                String ref = null;
                int backendNodeId = node.path("backendDOMNodeId").asInt(0);
                if (!ignored && backendNodeId > 0 && isInteractiveRole(role)) {
                    ref = "e" + refIndex++;
                    refs.put(ref, backendNodeId);
                }
                sb.append(formatSnapshotLine(role, name, ignored, ref));
                count++;
            }
        }
        elementRefs = Collections.unmodifiableMap(refs);
        return sb.toString();
    }

    public String click(String target) throws Exception {
        if (isRefToken(target)) {
            return clickRef(target);
        }
        return clickSelector(target);
    }

    private String clickSelector(String selector) throws Exception {
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

    private String clickRef(String ref) throws Exception {
        ensureConnected();
        String normalizedRef = normalizeRef(ref);
        String objectId = resolveObjectId(normalizedRef);
        if (objectId == null) {
            return "Element not found: @" + normalizedRef + " (call browser_snapshot first)";
        }
        return callFunctionOn(
            objectId,
            "function() { this.click(); return 'clicked'; }",
            null);
    }

    public String type(String target, String text, boolean clear) throws Exception {
        if (isRefToken(target)) {
            return typeRef(target, text, clear);
        }
        return typeSelector(target, text, clear);
    }

    private String typeRef(String ref, String text, boolean clear) throws Exception {
        ensureConnected();
        String normalizedRef = normalizeRef(ref);
        String objectId = resolveObjectId(normalizedRef);
        if (objectId == null) {
            return "Element not found: @" + normalizedRef + " (call browser_snapshot first)";
        }
        ArrayNode args = MAPPER.createArrayNode();
        args.addObject().put("value", text);
        args.addObject().put("value", clear);
        return callFunctionOn(
            objectId,
            "function(text, clear) {"
                + " this.focus();"
                + " const inputEvent = new Event('input', { bubbles: true });"
                + " const changeEvent = new Event('change', { bubbles: true });"
                + " if ('value' in this) {"
                + "   if (clear) { this.value = ''; }"
                + "   this.value += text;"
                + "   this.dispatchEvent(inputEvent);"
                + "   this.dispatchEvent(changeEvent);"
                + "   return 'typed';"
                + " }"
                + " if (this.isContentEditable) {"
                + "   if (clear) { this.textContent = ''; }"
                + "   this.textContent = (this.textContent || '') + text;"
                + "   this.dispatchEvent(inputEvent);"
                + "   return 'typed';"
                + " }"
                + " return 'Element is not typeable';"
                + "}",
            args);
    }

    private String typeSelector(String selector, String text, boolean clear) throws Exception {
        String safeText = MAPPER.writeValueAsString(text);
        String clearPrefix = clear ? "el.value = ''; " : "";
        String script;
        if (selector != null && !selector.isBlank()) {
            String safeSelector = MAPPER.writeValueAsString(selector);
            script = "(() => { const el = document.querySelector(" + safeSelector + "); if (el) { "
                + clearPrefix + "el.value += " + safeText
                + "; el.dispatchEvent(new Event('input', { bubbles: true })); return 'typed'; } return 'no element'; })()";
        } else {
            script = "(() => { const el = document.activeElement; if (el) { "
                + clearPrefix + "el.value += " + safeText
                + "; el.dispatchEvent(new Event('input', { bubbles: true })); return 'typed'; } return 'no element'; })()";
        }
        return evaluate(script);
    }

    public String screenshot() throws Exception {
        ScreenshotData capture = captureScreenshotData();
        if (!capture.success()) {
            return capture.error();
        }
        return "data:image/png;base64," + capture.base64();
    }

    public BrowserScreenshot captureScreenshot() throws Exception {
        ScreenshotData capture = captureScreenshotData();
        if (!capture.success()) {
            return BrowserScreenshot.failure(capture.error());
        }
        byte[] png;
        try {
            png = Base64.getDecoder().decode(capture.base64());
        } catch (IllegalArgumentException e) {
            return BrowserScreenshot.failure("Screenshot failed: invalid base64 data");
        }
        Path output = writeScreenshot(png);
        return BrowserScreenshot.success("data:image/png;base64," + capture.base64(), output);
    }

    private ScreenshotData captureScreenshotData() throws Exception {
        ensureConnected();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("format", "png");
        JsonNode result = cdpClient.send("Page.captureScreenshot", params).get(120, TimeUnit.SECONDS);
        JsonNode data = result.get("data");
        if (data == null || data.isNull() || data.asText().isBlank()) {
            return ScreenshotData.failure("Screenshot failed: no data");
        }
        return ScreenshotData.success(data.asText());
    }

    private Path writeScreenshot(byte[] bytes) throws java.io.IOException {
        Path cacheDir = Path.of(System.getProperty("user.home", "."), ".hermes", "cache", "screenshots")
            .toAbsolutePath()
            .normalize();
        Files.createDirectories(cacheDir);
        Path output = cacheDir.resolve("browser_screenshot_" + UUID.randomUUID().toString().replace("-", "") + ".png")
            .toAbsolutePath()
            .normalize();
        if (!output.startsWith(cacheDir)) {
            throw new IllegalArgumentException("Resolved browser screenshot path escaped cache directory");
        }
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return output;
    }

    public String evaluate(String expression) throws Exception {
        enforceExpressionUrlSafety(expression);
        ensureConnected();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        JsonNode result = cdpClient.send("Runtime.evaluate", params).get(60, TimeUnit.SECONDS);
        return runtimeResultToString(result);
    }

    public String console(boolean clear) throws Exception {
        ensureConnected();
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", true);
        ArrayNode messages = response.putArray("console_messages");
        for (ConsoleMessage message : consoleMessages) {
            ObjectNode item = messages.addObject();
            item.put("type", message.type());
            item.put("text", message.text());
            item.put("source", message.source());
        }
        ArrayNode errors = response.putArray("js_errors");
        for (JsError error : jsErrors) {
            ObjectNode item = errors.addObject();
            item.put("message", error.message());
            item.put("source", error.source());
        }
        response.put("total_messages", messages.size());
        response.put("total_errors", errors.size());
        if (clear) {
            consoleMessages.clear();
            jsErrors.clear();
        }
        return MAPPER.writeValueAsString(response);
    }

    public String rawCdp(String method, JsonNode params, int timeoutSeconds) throws Exception {
        ObjectNode objectParams = null;
        if (params != null && !params.isNull() && !params.isMissingNode()) {
            if (!params.isObject()) {
                throw new IllegalArgumentException("params must be a JSON object");
            }
            objectParams = (ObjectNode) params;
        }
        enforceRawCdpUrlSafety(method, objectParams);
        ensureConnected();
        int timeout = timeoutSeconds > 0 ? Math.min(timeoutSeconds, 300) : 30;
        JsonNode result = cdpClient.send(method, objectParams).get(timeout, TimeUnit.SECONDS);
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", true);
        response.put("method", method);
        response.set("result", result == null ? MAPPER.nullNode() : result);
        return MAPPER.writeValueAsString(response);
    }

    private void enforceRawCdpUrlSafety(String method, ObjectNode params) {
        if (method == null || params == null) {
            return;
        }
        if ("Page.navigate".equals(method)) {
            String url = params.path("url").asText(null);
            String blockReason = browserUrlBlockReason(url);
            if (blockReason != null) {
                throw new IllegalArgumentException(blockReason);
            }
            return;
        }
        if ("Runtime.evaluate".equals(method)) {
            enforceExpressionUrlSafety(params.path("expression").asText(null));
            return;
        }
        if ("Runtime.callFunctionOn".equals(method)) {
            enforceExpressionUrlSafety(params.path("functionDeclaration").asText(null));
        }
    }

    private void enforceExpressionUrlSafety(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        Matcher matcher = JS_HTTP_URL_LITERAL.matcher(expression);
        while (matcher.find()) {
            String candidate = trimTrailingUrlPunctuation(matcher.group());
            String blockReason = browserUrlBlockReason(candidate);
            if (blockReason != null) {
                throw new IllegalArgumentException(
                    "JavaScript expression targets a blocked URL (" + candidate + "): " + blockReason
                );
            }
        }
    }

    private String redirectBlockReason(String originalUrl) {
        String currentUrl = currentPageUrl();
        if (currentUrl == null || currentUrl.isBlank() || currentUrl.equals(originalUrl)) {
            return null;
        }
        String blockReason = browserUrlBlockReason(currentUrl);
        if (blockReason == null) {
            return null;
        }
        if (blockReason.startsWith("URL blocked by safety policy:")) {
            return "URL blocked by safety policy after redirect: " + currentUrl;
        }
        return blockReason + " after redirect to " + currentUrl;
    }

    private String currentPageUrl() {
        try {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("expression", "window.location.href");
            params.put("returnByValue", true);
            JsonNode result = cdpClient.send("Runtime.evaluate", params).get(5, TimeUnit.SECONDS);
            JsonNode value = result.path("result").path("value");
            return value.isTextual() ? value.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String browserUrlBlockReason(String url) {
        if (urlSafety == null || !urlSafety.isUrlAllowed(url)) {
            return "URL blocked by safety policy: " + url;
        }
        if (websitePolicy != null) {
            String websiteBlock = websitePolicy.checkAccess(url);
            if (websiteBlock != null) {
                return websiteBlock;
            }
        }
        return null;
    }

    private String trimTrailingUrlPunctuation(String url) {
        String candidate = url == null ? "" : url;
        while (!candidate.isEmpty()) {
            char last = candidate.charAt(candidate.length() - 1);
            if (last != '.' && last != ',' && last != ';' && last != ':') {
                break;
            }
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private String callFunctionOn(String objectId, String functionDeclaration, ArrayNode args) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("objectId", objectId);
        params.put("functionDeclaration", functionDeclaration);
        params.put("returnByValue", true);
        if (args != null) {
            params.set("arguments", args);
        }
        JsonNode result = cdpClient.send("Runtime.callFunctionOn", params).get(60, TimeUnit.SECONDS);
        return runtimeResultToString(result);
    }

    private String runtimeResultToString(JsonNode result) throws Exception {
        JsonNode exception = result.path("exceptionDetails");
        if (!exception.isMissingNode()) {
            String text = exception.path("text").asText();
            String description = exception.path("exception").path("description").asText();
            String message = !description.isBlank() ? description : text;
            return "Evaluation error: " + (message.isBlank() ? exception.toString() : message);
        }

        JsonNode remoteObject = result.path("result");
        JsonNode value = remoteObject.path("value");
        if (!value.isMissingNode()) {
            if (value.isTextual()) {
                return value.asText();
            }
            return MAPPER.writeValueAsString(value);
        }
        JsonNode description = remoteObject.path("description");
        if (description.isTextual() && !description.asText().isBlank()) {
            return description.asText();
        }
        return result.toString();
    }

    private String resolveObjectId(String normalizedRef) throws Exception {
        Integer backendNodeId = elementRefs.get(normalizedRef);
        if (backendNodeId == null) {
            return null;
        }
        ObjectNode params = MAPPER.createObjectNode();
        params.put("backendNodeId", backendNodeId);
        JsonNode result = cdpClient.send("DOM.resolveNode", params).get(60, TimeUnit.SECONDS);
        String objectId = result.path("object").path("objectId").asText("");
        return objectId.isBlank() ? null : objectId;
    }

    private boolean isInteractiveRole(String role) {
        return role != null && INTERACTIVE_ROLES.contains(role.toLowerCase(Locale.ROOT));
    }

    private String formatSnapshotLine(String role, String name, boolean ignored, String ref) {
        StringBuilder line = new StringBuilder();
        if (ref != null) {
            line.append(role.isBlank() ? "element" : role).append(" [ref=").append(ref).append("]");
            if (!name.isBlank()) {
                line.append(": ").append(name);
            }
        } else {
            line.append("[").append(role).append("] ");
            if (!name.isBlank()) {
                line.append(name);
            }
        }
        if (ignored) {
            line.append(" [ignored]");
        }
        return line.append("\n").toString();
    }

    private boolean isRefToken(String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        return normalizeRef(target).matches("e\\d+");
    }

    private String normalizeRef(String ref) {
        String value = ref == null ? "" : ref.trim();
        return value.startsWith("@") ? value.substring(1) : value;
    }

    public String press(String key) throws Exception {
        if (key == null || key.isBlank()) {
            return "Key is required";
        }
        ensureConnected();
        String normalized = canonicalKey(key.trim());
        dispatchKeyEvent("keyDown", normalized);
        dispatchKeyEvent("keyUp", normalized);
        return "Pressed " + normalized;
    }

    private String canonicalKey(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "enter", "return" -> "Enter";
            case "escape", "esc" -> "Escape";
            case "tab" -> "Tab";
            case "backspace" -> "Backspace";
            case "delete", "del" -> "Delete";
            case "arrowup", "up" -> "ArrowUp";
            case "arrowdown", "down" -> "ArrowDown";
            case "arrowleft", "left" -> "ArrowLeft";
            case "arrowright", "right" -> "ArrowRight";
            case "space" -> " ";
            default -> key;
        };
    }

    private void dispatchKeyEvent(String type, String key) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", type);
        params.put("key", key);
        params.put("code", keyCode(key));
        int virtualKeyCode = windowsVirtualKeyCode(key);
        if (virtualKeyCode > 0) {
            params.put("windowsVirtualKeyCode", virtualKeyCode);
            params.put("nativeVirtualKeyCode", virtualKeyCode);
        }
        cdpClient.send("Input.dispatchKeyEvent", params).get(60, TimeUnit.SECONDS);
    }

    private String keyCode(String key) {
        return switch (key) {
            case "Enter" -> "Enter";
            case "Escape", "Esc" -> "Escape";
            case "Tab" -> "Tab";
            case "Backspace" -> "Backspace";
            case "Delete" -> "Delete";
            case "ArrowUp" -> "ArrowUp";
            case "ArrowDown" -> "ArrowDown";
            case "ArrowLeft" -> "ArrowLeft";
            case "ArrowRight" -> "ArrowRight";
            case " " -> "Space";
            default -> key.length() == 1 ? "Key" + key.toUpperCase(Locale.ROOT) : key;
        };
    }

    private int windowsVirtualKeyCode(String key) {
        return switch (key) {
            case "Enter" -> 13;
            case "Escape", "Esc" -> 27;
            case "Tab" -> 9;
            case "Backspace" -> 8;
            case "Delete" -> 46;
            case "ArrowUp" -> 38;
            case "ArrowDown" -> 40;
            case "ArrowLeft" -> 37;
            case "ArrowRight" -> 39;
            case " " -> 32;
            default -> key.length() == 1 ? Character.toUpperCase(key.charAt(0)) : 0;
        };
    }

    private void ensureConnected() throws Exception {
        String unsupportedProvider = unsupportedBrowserProviderMessage();
        if (unsupportedProvider != null) {
            throw new IllegalStateException(unsupportedProvider);
        }
        if (!cdpClient.isConnected()) {
            try {
                // Always re-discover the WebSocket URL from the HTTP endpoint; the browser
                // may have been restarted, making the old webSocketUrl stale.
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
        registerConsoleListenersIfNeeded();
    }

    String unsupportedBrowserProviderMessage() {
        String provider = properties == null || properties.getBrowser() == null
            ? "local"
            : properties.getBrowser().getCloudProvider();
        String normalized = provider == null ? "local" : provider.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || "local".equals(normalized)) {
            return null;
        }
        return "Browser provider '" + normalized + "' is configured, but java-agent currently supports only local CDP browser mode. "
            + "Set agent.browser.cloud-provider=local or use Hermes/browser provider runtime for cloud, hybrid, browser-use, or Camofox modes.";
    }

    private void registerConsoleListenersIfNeeded() {
        if (!consoleListenersRegistered.compareAndSet(false, true)) {
            return;
        }
        cdpClient.onEvent("Runtime.consoleAPICalled", this::recordRuntimeConsole);
        cdpClient.onEvent("Runtime.exceptionThrown", this::recordRuntimeException);
        cdpClient.onEvent("Log.entryAdded", this::recordLogEntry);
        try {
            CompletableFuture<JsonNode> enabled = cdpClient.send("Log.enable", null);
            if (enabled != null) {
                enabled.get(5, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // Runtime console events are still useful if the Log domain is unavailable.
        }
    }

    private void recordRuntimeConsole(JsonNode params) {
        String type = params.path("type").asText("log");
        String text = runtimeArgsToText(params.path("args"));
        addBounded(consoleMessages, new ConsoleMessage(type.isBlank() ? "log" : type, text, "console"));
    }

    private void recordRuntimeException(JsonNode params) {
        JsonNode details = params.path("exceptionDetails");
        String description = details.path("exception").path("description").asText("");
        String text = details.path("text").asText("");
        String message = !description.isBlank() ? description : text;
        if (message.isBlank()) {
            message = details.toString();
        }
        addBounded(jsErrors, new JsError(message, "exception"));
    }

    private void recordLogEntry(JsonNode params) {
        JsonNode entry = params.path("entry");
        String type = entry.path("level").asText("log");
        String text = entry.path("text").asText("");
        String source = entry.path("source").asText("log");
        addBounded(consoleMessages, new ConsoleMessage(
            type.isBlank() ? "log" : type,
            text,
            source.isBlank() ? "log" : source));
    }

    private String runtimeArgsToText(JsonNode args) {
        if (args == null || !args.isArray() || args.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode arg : args) {
            parts.add(remoteObjectToText(arg));
        }
        return String.join(" ", parts);
    }

    private String remoteObjectToText(JsonNode value) {
        JsonNode rawValue = value.path("value");
        if (!rawValue.isMissingNode()) {
            return rawValue.isTextual() ? rawValue.asText() : rawValue.toString();
        }
        String unserializable = value.path("unserializableValue").asText("");
        if (!unserializable.isBlank()) {
            return unserializable;
        }
        String description = value.path("description").asText("");
        if (!description.isBlank()) {
            return description;
        }
        return value.path("type").asText("");
    }

    private <T> void addBounded(ConcurrentLinkedDeque<T> buffer, T value) {
        buffer.addLast(value);
        while (buffer.size() > MAX_CONSOLE_EVENTS) {
            buffer.pollFirst();
        }
    }

    private void waitForLoad() throws Exception {
        waitForLoad(30);
    }

    private void waitForLoad(int timeoutSeconds) throws Exception {
        cdpClient.waitForEvent("Page.loadEventFired", timeoutSeconds).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    private record ConsoleMessage(String type, String text, String source) {}

    private record JsError(String message, String source) {}

    private record ScreenshotData(boolean success, String base64, String error) {
        static ScreenshotData success(String base64) {
            return new ScreenshotData(true, base64, null);
        }

        static ScreenshotData failure(String error) {
            return new ScreenshotData(false, null, error);
        }
    }

    public record BrowserScreenshot(boolean success,
                                    String dataUrl,
                                    String screenshotPath,
                                    String mediaTag,
                                    String mimeType,
                                    String error) {
        static BrowserScreenshot success(String dataUrl, Path path) {
            String normalizedPath = path.toAbsolutePath().normalize().toString();
            return new BrowserScreenshot(true, dataUrl, normalizedPath, "MEDIA:" + normalizedPath, "image/png", null);
        }

        static BrowserScreenshot failure(String error) {
            return new BrowserScreenshot(false, null, null, null, null, error);
        }
    }
}
