package com.azhukov.agent.tools.browser.backend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * WP-8 (docs/35): extension-control browser backend.
 *
 * <p>Drives the USER'S OWN browser through a browser-extension control
 * bridge (preferred non-local route: user browser ownership is preserved).
 * Transport: authorized HTTP bridge (shared secret token, constant-time
 * compare on the bridge; the token is sent as a bearer header, never in
 * URLs). The bridge is expected at
 * {@code agent.browser.extension.bridge-url} and every request carries the
 * session token; responses are plain JSON. All URLs pass the router guard
 * before dispatch and the final (post-redirect) URL is re-validated.
 */
@Component
@ConditionalOnProperty("agent.browser.extension.token")
@Slf4j
public class ExtensionBrowserBackend implements BrowserBackend {

    private final String bridgeUrl;
    private final String token;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public ExtensionBrowserBackend(
            @org.springframework.beans.factory.annotation.Value(
                "${agent.browser.extension.bridge-url:http://127.0.0.1:8819}") String bridgeUrl,
            @org.springframework.beans.factory.annotation.Value(
                "${agent.browser.extension.token}") String token) {
        this.bridgeUrl = bridgeUrl == null || bridgeUrl.isBlank()
            ? "http://127.0.0.1:8819" : bridgeUrl.trim();
        this.token = token;
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    @Override
    public String id() {
        return BrowserBackendRouter.PROVIDER_EXTENSION;
    }

    @Override
    public Set<Capability> capabilities() {
        // The user's browser performs rendering; vision/raw-CDP stay local-only.
        return Set.of(Capability.NAVIGATE, Capability.DOM_SNAPSHOT, Capability.CLICK,
            Capability.TYPE, Capability.SCREENSHOT, Capability.SESSION_LIFECYCLE);
    }

    @Override
    public BrowserBackendException normalizeError(Exception raw) {
        if (raw instanceof BrowserBackendException typed) {
            return typed;
        }
        String message = raw.getMessage() == null ? raw.getClass().getSimpleName() : raw.getMessage();
        if (raw instanceof java.util.concurrent.TimeoutException) {
            return new BrowserBackendException(BrowserBackendException.Kind.TIMEOUT,
                "extension bridge timeout: " + message, raw);
        }
        if (raw instanceof java.net.ConnectException || raw instanceof java.net.UnknownHostException) {
            return new BrowserBackendException(BrowserBackendException.Kind.PROVIDER_UNAVAILABLE,
                "extension bridge unreachable (is the browser extension running?): " + message, raw);
        }
        return new BrowserBackendException(BrowserBackendException.Kind.PROVIDER_UNAVAILABLE,
            "extension bridge error: " + message, raw);
    }

    @Override
    public SessionHandle openSession(URI targetUrl, UrlGuard guard) throws BrowserBackendException {
        guard.check(targetUrl);
        String sessionId = "ext-" + UUID.randomUUID().toString().replace("-", "");
        // Bridge opens a tracking tab; final URL comes back post-redirect and is re-guarded.
        NavigationResult landed = callBridge("navigate", sessionId, targetUrl.toString(), guard);
        return new ExtensionSession(sessionId, landed);
    }

    private NavigationResult callBridge(String op, String sessionId, String url, UrlGuard guard)
            throws BrowserBackendException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(bridgeUrl + "/" + op))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"session_id\":\"" + sessionId + "\",\"url\":\"" + url + "\"}"))
                .build();
            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new BrowserBackendException(BrowserBackendException.Kind.AUTH_FAILED,
                    "extension bridge rejected the token (status " + response.statusCode() + ")");
            }
            if (response.statusCode() != 200) {
                throw new BrowserBackendException(BrowserBackendException.Kind.PROVIDER_UNAVAILABLE,
                    "extension bridge returned status " + response.statusCode());
            }
            String finalUrl = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response.body()).path("final_url").asText(url);
            if (guard != null && finalUrl != null && !finalUrl.isBlank()) {
                guard.check(URI.create(finalUrl)); // redirect re-validation
            }
            return new NavigationResult(finalUrl, response.statusCode(), url);
        } catch (BrowserBackendException e) {
            throw e;
        } catch (Exception e) {
            throw normalizeError(e);
        }
    }

    private final class ExtensionSession implements SessionHandle {
        private final String sessionId;
        private volatile NavigationResult current;

        ExtensionSession(String sessionId, NavigationResult initial) {
            this.sessionId = sessionId;
            this.current = initial;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public NavigationResult navigate(URI url, UrlGuard guard) throws BrowserBackendException {
            this.current = callBridge("navigate", sessionId, url.toString(), guard);
            return current;
        }

        @Override
        public String domSnapshot() throws BrowserBackendException {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(bridgeUrl + "/snapshot"))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"session_id\":\"" + sessionId + "\"}"))
                    .build();
                HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new BrowserBackendException(BrowserBackendException.Kind.SESSION_LOST,
                        "snapshot failed with status " + response.statusCode());
                }
                return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(response.body()).path("text").asText("");
            } catch (BrowserBackendException e) {
                throw e;
            } catch (Exception e) {
                throw normalizeError(e);
            }
        }

        @Override
        public void click(String selector) throws BrowserBackendException {
            dispatch("click", selector, null);
        }

        @Override
        public void type(String selector, String text) throws BrowserBackendException {
            dispatch("type", selector, text);
        }

        @Override
        public Path screenshot() throws BrowserBackendException {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(bridgeUrl + "/screenshot"))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"session_id\":\"" + sessionId + "\"}"))
                    .build();
                HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new BrowserBackendException(BrowserBackendException.Kind.SESSION_LOST,
                        "screenshot failed with status " + response.statusCode());
                }
                Path file = Files.createTempFile("ext-browser-", ".png");
                Files.write(file, response.body());
                return file;
            } catch (BrowserBackendException e) {
                throw e;
            } catch (Exception e) {
                throw normalizeError(e);
            }
        }

        private void dispatch(String op, String selector, String text) throws BrowserBackendException {
            try {
                String payload = "{\"session_id\":\"" + sessionId
                    + "\",\"selector\":\"" + selector + "\""
                    + (text == null ? "" : ",\"text\":\"" + text + "\"") + "}";
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(bridgeUrl + "/" + op))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
                HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new BrowserBackendException(BrowserBackendException.Kind.SESSION_LOST,
                        op + " failed with status " + response.statusCode());
                }
            } catch (BrowserBackendException e) {
                throw e;
            } catch (Exception e) {
                throw normalizeError(e);
            }
        }

        @Override
        public void close() {
            // Best-effort close; idempotent by contract.
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(bridgeUrl + "/close"))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"session_id\":\"" + sessionId + "\"}"))
                    .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.debug("Extension session close failed for {}: {}", sessionId, e.getMessage());
            }
        }
    }
}
