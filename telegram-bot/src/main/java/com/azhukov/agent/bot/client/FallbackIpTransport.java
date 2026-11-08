package com.azhukov.agent.bot.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP transport interceptor that retries Telegram Bot API requests via fallback IPs
 * when the primary connection is unreachable.
 *
 * <p>Requests continue to target {@code https://api.telegram.org/...} logically,
 * but on connect failures the underlying TCP connection is retried against a known
 * reachable IP. This is equivalent to {@code curl --resolve api.telegram.org:443:<ip>}.
 *
 * <p>Features:
 * <ul>
 *   <li>Sticky-IP optimization — once a fallback IP works, it's tried first</li>
 *   <li>Automatic reset of sticky IP when it fails</li>
 *   <li>Host header and logical URL preserved as api.telegram.org</li>
 * </ul>
 *
 * <p>Mirrors the Python {@code TelegramFallbackTransport} in
 * {@code gateway/platforms/telegram_network.py}.
 */
@Slf4j
public class FallbackIpTransport implements ClientHttpRequestInterceptor {

    public static final String TELEGRAM_API_HOST = "api.telegram.org";

    private final List<String> fallbackIps;
    private final AtomicReference<String> stickyIp = new AtomicReference<>();

    /**
     * @param fallbackIps raw fallback IP list (validated and deduplicated internally)
     */
    public FallbackIpTransport(List<String> fallbackIps) {
        this.fallbackIps = FallbackIpValidator.normalize(fallbackIps);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (!isTelegramApiRequest(request) || fallbackIps.isEmpty()) {
            return execution.execute(request, body);
        }

        List<String> attemptOrder = buildAttemptOrder();
        Exception lastError = null;

        for (String ip : attemptOrder) {
            try {
                HttpRequest candidate = ip == null ? request : rewriteForIp(request, ip);
                ClientHttpResponse response = execution.execute(candidate, body);
                if (ip != null) {
                    setStickyIp(ip);
                }
                return response;
            } catch (Exception e) {
                if (!isRetryableConnectError(e)) {
                    throw e;
                }
                lastError = e;
                if (ip != null && ip.equals(stickyIp.get())) {
                    log.warn("[Telegram] Sticky fallback IP {} failed; resetting to primary DNS path", ip);
                    stickyIp.set(null);
                }
                if (ip == null) {
                    log.warn("[Telegram] Primary api.telegram.org connection failed ({}); trying fallback IPs {}",
                        e.getMessage(), String.join(", ", fallbackIps));
                } else {
                    log.warn("[Telegram] Fallback IP {} failed: {}", ip, e.getMessage());
                }
            }
        }

        throw new IOException("All Telegram fallback IPs exhausted", lastError);
    }

    /**
     * Build the attempt order: sticky IP first (if any), then primary (null), then remaining fallbacks.
     */
    private List<String> buildAttemptOrder() {
        List<String> order = new ArrayList<>();
        String sticky = stickyIp.get();
        if (sticky != null) {
            order.add(sticky);
        }
        order.add(null); // primary DNS path
        for (String ip : fallbackIps) {
            if (!ip.equals(sticky)) {
                order.add(ip);
            }
        }
        return order;
    }

    private void setStickyIp(String ip) {
        String current = stickyIp.get();
        if (current == null || !current.equals(ip)) {
            stickyIp.set(ip);
            log.warn("[Telegram] Primary api.telegram.org path unreachable; using sticky fallback IP {}", ip);
        }
    }

    /**
     * Rewrite a request to target a specific fallback IP while preserving the Host header.
     */
    static HttpRequest rewriteForIp(HttpRequest original, String ip) {
        URI originalUri = original.getURI();
        String originalHost = originalUri.getHost();
        String newUriStr = originalUri.toString().replace(originalHost, ip);
        URI newUri = URI.create(newUriStr);

        return new HttpRequest() {
            @Override
            public HttpMethod getMethod() {
                return original.getMethod();
            }

            @Override
            public URI getURI() {
                return newUri;
            }

            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.addAll(original.getHeaders());
                headers.set("Host", originalHost);
                return headers;
            }

            @Override
            public java.util.Map<String, Object> getAttributes() {
                return original.getAttributes();
            }
        };
    }

    static boolean isTelegramApiRequest(HttpRequest request) {
        URI uri = request.getURI();
        return uri != null && TELEGRAM_API_HOST.equals(uri.getHost());
    }

    static boolean isRetryableConnectError(Exception e) {
        if (e instanceof java.net.ConnectException) return true;
        if (e instanceof java.net.SocketTimeoutException) return true;
        if (e instanceof org.springframework.web.client.ResourceAccessException) return true;
        Throwable cause = e.getCause();
        if (cause instanceof java.net.ConnectException) return true;
        if (cause instanceof java.net.SocketTimeoutException) return true;
        return false;
    }

    /**
     * @return the current sticky fallback IP, or null if none is set
     */
    public String getStickyIp() {
        return stickyIp.get();
    }

    /**
     * @return immutable copy of the fallback IP list
     */
    public List<String> getFallbackIps() {
        return List.copyOf(fallbackIps);
    }

    /**
     * Reset the sticky IP (for testing).
     */
    void resetStickyIp() {
        stickyIp.set(null);
    }
}