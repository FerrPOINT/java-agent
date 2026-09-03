package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class SsrfSafeHttpClient {

    private static final int MAX_SAFE_REDIRECTS = 10;

    private final UrlSafetyHandler safety;
    private final SecretRedactor redactor;
    private final String userAgent;

    public SsrfSafeHttpClient(UrlSafetyHandler safety, SecretRedactor redactor, AgentProperties properties) {
        this.safety = safety;
        this.redactor = redactor;
        this.userAgent = properties.getCore().getHttpUserAgent();
    }

    /**
     * Creates a fresh HttpClient with a per-request timeout so concurrent calls
     * don't race to mutate shared timeout or redirect state.
     */
    private HttpClient createClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public String fetch(String url, int timeoutSeconds) {
        return sendWithSafeRedirects("GET", url, null, timeoutSeconds);
    }

    public String post(String url, String body, int timeoutSeconds) {
        return sendWithSafeRedirects("POST", url, body == null ? "" : body, timeoutSeconds);
    }

    private String sendWithSafeRedirects(String method, String url, String body, int timeoutSeconds) {
        String error = safety.checkUrl(url);
        if (error != null) {
            throw new SecurityException(error);
        }
        HttpClient client = createClient();
        URI current = URI.create(url);
        String currentMethod = method;
        String currentBody = body;
        try {
            for (int redirectCount = 0; redirectCount <= MAX_SAFE_REDIRECTS; redirectCount++) {
                HttpRequest request = buildRequest(current, currentMethod, currentBody, timeoutSeconds);
                HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );

                int status = response.statusCode();
                if (!isRedirectStatus(status)) {
                    if (status >= 400) {
                        throw new IllegalStateException("HTTP " + status + " from " + current);
                    }
                    return redactor.redact(response.body() == null ? "" : response.body());
                }

                String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("Redirect response missing Location header"));
                current = current.resolve(location);
                String blockReason = safety.checkUrl(current.toString());
                if (blockReason != null) {
                    throw new SecurityException("Redirect blocked: " + blockReason);
                }
                if ("POST".equals(currentMethod) && (status == 301 || status == 302 || status == 303)) {
                    currentMethod = "GET";
                    currentBody = null;
                }
            }
            throw new IllegalStateException("Too many redirects");
        } catch (IOException e) {
            throw new IllegalStateException("HTTP request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted", e);
        }
    }

    private HttpRequest buildRequest(URI uri, String method, String body, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
            .header("User-Agent", userAgent);
        if ("POST".equals(method)) {
            return builder
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
                .build();
        }
        return builder.GET().build();
    }

    private boolean isRedirectStatus(int statusCode) {
        return statusCode >= 300 && statusCode < 400;
    }
}
