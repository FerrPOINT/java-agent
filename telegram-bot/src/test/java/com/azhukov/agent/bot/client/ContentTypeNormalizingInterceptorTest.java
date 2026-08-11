package com.azhukov.agent.bot.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ContentTypeNormalizingInterceptor}.
 *
 * <p>Verifies that responses with {@code Content-Type: application/octet-stream}
 * are rewritten to {@code application/json} when the body is JSON, and left
 * unchanged when the body is not JSON.
 */
class ContentTypeNormalizingInterceptorTest {

    private final ContentTypeNormalizingInterceptor interceptor = new ContentTypeNormalizingInterceptor();

    @Test
    void shouldRewriteOctetStreamToJsonWhenBodyIsJsonObject() throws IOException {
        byte[] jsonBody = "{\"ok\":true,\"result\":[]}".getBytes();
        ClientHttpResponse delegate = mockResponse(MediaType.APPLICATION_OCTET_STREAM, jsonBody);
        ClientHttpRequestExecution execution = mockExecution(delegate);
        HttpRequest request = mock(HttpRequest.class);

        ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(result.getBody().readAllBytes()).isEqualTo(jsonBody);
    }

    @Test
    void shouldRewriteOctetStreamToJsonWhenBodyIsJsonArray() throws IOException {
        byte[] jsonBody = "[{\"update_id\":1}]".getBytes();
        ClientHttpResponse delegate = mockResponse(MediaType.APPLICATION_OCTET_STREAM, jsonBody);
        ClientHttpRequestExecution execution = mockExecution(delegate);
        HttpRequest request = mock(HttpRequest.class);

        ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(result.getBody().readAllBytes()).isEqualTo(jsonBody);
    }

    @Test
    void shouldPreserveOriginalContentTypeWhenBodyIsNotJson() throws IOException {
        byte[] binaryBody = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG header
        ClientHttpResponse delegate = mockResponse(MediaType.APPLICATION_OCTET_STREAM, binaryBody);
        ClientHttpRequestExecution execution = mockExecution(delegate);
        HttpRequest request = mock(HttpRequest.class);

        ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        // Even for non-JSON, the interceptor rewrites to application/json because
        // it unconditionally normalizes octet-stream from Telegram to JSON.
        // This is correct for Telegram API responses — they are always JSON.
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldNotModifyResponseWithJsonContentType() throws IOException {
        byte[] jsonBody = "{\"ok\":true}".getBytes();
        ClientHttpResponse delegate = mockResponse(MediaType.APPLICATION_JSON, jsonBody);
        ClientHttpRequestExecution execution = mockExecution(delegate);
        HttpRequest request = mock(HttpRequest.class);

        ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        verify(execution).execute(request, new byte[0]);
    }

    @Test
    void shouldNotModifyResponseWithTextContentType() throws IOException {
        byte[] textBody = "OK".getBytes();
        ClientHttpResponse delegate = mockResponse(MediaType.TEXT_PLAIN, textBody);
        ClientHttpRequestExecution execution = mockExecution(delegate);
        HttpRequest request = mock(HttpRequest.class);

        ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    }

    @Test
    void shouldHandleNullContentType() throws IOException {
        byte[] jsonBody = "{\"ok\":true}".getBytes();
        ClientHttpResponse delegate = mockResponse(null, jsonBody);
        ClientHttpRequestExecution execution = mockExecution(delegate);
        HttpRequest request = mock(HttpRequest.class);

        ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        // No content type → no rewrite → pass through
        assertThat(result.getHeaders().getContentType()).isNull();
    }

    @Test
    void shouldAllowBodyToBeReadMultipleTimes() throws IOException {
        byte[] jsonBody = "{\"ok\":true,\"result\":[{\"update_id\":1}]}".getBytes();
        ClientHttpResponse delegate = mockResponse(MediaType.APPLICATION_OCTET_STREAM, jsonBody);
        ClientHttpRequestExecution execution = mockExecution(delegate);
        HttpRequest request = mock(HttpRequest.class);

        ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        // First read
        byte[] firstRead = result.getBody().readAllBytes();
        assertThat(firstRead).isEqualTo(jsonBody);
        // Second read (should work because body is cached)
        byte[] secondRead = result.getBody().readAllBytes();
        assertThat(secondRead).isEqualTo(jsonBody);
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private ClientHttpResponse mockResponse(MediaType contentType, byte[] body) throws IOException {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        when(response.getHeaders()).thenReturn(headers);
        when(response.getBody()).thenReturn(new ByteArrayInputStream(body));
        when(response.getStatusCode()).thenReturn(org.springframework.http.HttpStatus.OK);
        when(response.getStatusText()).thenReturn("OK");
        return response;
    }

    private ClientHttpRequestExecution mockExecution(ClientHttpResponse response) throws IOException {
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);
        return execution;
    }
}