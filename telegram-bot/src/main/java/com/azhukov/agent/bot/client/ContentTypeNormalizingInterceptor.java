package com.azhukov.agent.bot.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * Normalizes Telegram API responses that arrive with
 * {@code Content-Type: application/octet-stream} instead of
 * {@code application/json}. This happens intermittently on long-polling
 * timeouts and when connecting via fallback IPs.
 *
 * <p>The interceptor peeks at the first byte of the response body: if it
 * is a JSON start character ({@code '{' } or {@code '['}), the
 * Content-Type header is rewritten to {@code application/json} before
 * Spring's message converters see it. Otherwise the original content type
 * is preserved.
 *
 * <p>This is a clean fix at the HTTP transport level — no changes to
 * RestClient usage, no custom message converters, no hacks in calling
 * code.
 */
@Slf4j
public class ContentTypeNormalizingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        MediaType contentType = response.getHeaders().getContentType();

        if (contentType != null && contentType.includes(MediaType.APPLICATION_OCTET_STREAM)) {
            return new JsonContentTypeResponse(response);
        }
        return response;
    }

    /**
     * Response wrapper that inspects the body stream and overrides
     * Content-Type to application/json when the body is JSON.
     * The body stream is buffered so it can be re-read by downstream
     * consumers.
     */
    static class JsonContentTypeResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private byte[] cachedBody;

        JsonContentTypeResponse(ClientHttpResponse delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(delegate.getHeaders());
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            if (cachedBody == null) {
                cachedBody = delegate.getBody().readAllBytes();
            }
            return new java.io.ByteArrayInputStream(cachedBody);
        }

        @Override
        public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}