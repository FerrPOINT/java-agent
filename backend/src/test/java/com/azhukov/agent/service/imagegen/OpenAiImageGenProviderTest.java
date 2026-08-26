package com.azhukov.agent.service.imagegen;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenAiImageGenProvider}.
 * Covers: happy path, null/blank input, API error handling, edge cases.
 * Uses MockedStatic to intercept HttpClient.newBuilder() and inject a mock client.
 */
@ExtendWith(MockitoExtension.class)
class OpenAiImageGenProviderTest {

    private AgentProperties properties;
    private OpenAiImageGenProvider provider;
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getImageGen().setApiKey("test-api-key");
        properties.getImageGen().setModel("dall-e-3");

        mockHttpClient = mock(HttpClient.class);
        provider = createProviderWithMockedClient();
    }

    // ── Happy path ──

    @Test
    void generate_returnsImageBytes_whenApiReturnsValidB64() throws Exception {
        byte[] imageBytes = {(byte) 0x89, 0x50, 0x4E, 0x47};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("a cat painting", "1:1");

        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_usesDefaultModel_whenModelIsBlank() throws Exception {
        properties.getImageGen().setModel("");
        provider = createProviderWithMockedClient();

        byte[] imageBytes = {1, 2, 3};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("test prompt", null);

        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_usesDefaultModel_whenModelIsNull() throws Exception {
        properties.getImageGen().setModel(null);
        provider = createProviderWithMockedClient();

        byte[] imageBytes = {1, 2, 3};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("test prompt", null);

        assertThat(result).isEqualTo(imageBytes);
    }

    // ── Aspect ratio mapping ──

    @Test
    void generate_mapsLandscapeAspectRatio_correctly() throws Exception {
        byte[] imageBytes = {1, 2, 3};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("landscape image", "16:9");
        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_mapsPortraitAspectRatio_correctly() throws Exception {
        byte[] imageBytes = {1, 2, 3};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("portrait image", "9:16");
        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_mapsLandscapeString_correctly() throws Exception {
        byte[] imageBytes = {1};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("landscape", "landscape");
        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_mapsPortraitString_correctly() throws Exception {
        byte[] imageBytes = {1};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("portrait", "portrait");
        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_mapsUnknownAspectRatio_toDefault() throws Exception {
        byte[] imageBytes = {1};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("test", "4:3");
        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_mapsNullAspectRatio_toDefault() throws Exception {
        byte[] imageBytes = {1};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("test", null);
        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_mapsBlankAspectRatio_toDefault() throws Exception {
        byte[] imageBytes = {1};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("test", "  ");
        assertThat(result).isEqualTo(imageBytes);
    }

    // ── API error handling ──

    @Test
    void generate_throws_whenHttpStatusIsNot200() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.body()).thenReturn("{\"error\":{\"message\":\"Unauthorized\"}}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        assertThatThrownBy(() -> provider.generate("test", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP 401");
    }

    @Test
    void generate_throws_whenHttpStatus500() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("{\"error\":\"server error\"}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        assertThatThrownBy(() -> provider.generate("test", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP 500");
    }

    @Test
    void generate_throws_whenDataArrayIsMissing() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"foo\":\"bar\"}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        assertThatThrownBy(() -> provider.generate("test", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("no data");
    }

    @Test
    void generate_throws_whenDataArrayIsEmpty() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[]}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        assertThatThrownBy(() -> provider.generate("test", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("no data");
    }

    @Test
    void generate_throws_whenB64JsonIsBlank() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"b64_json\":\"\"}]}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        assertThatThrownBy(() -> provider.generate("test", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("empty b64_json");
    }

    @Test
    void generate_throws_whenB64JsonIsMissing() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"url\":\"http://example.com/image.png\"}]}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        assertThatThrownBy(() -> provider.generate("test", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("empty b64_json");
    }

    @Test
    void generate_throws_whenHttpClientThrowsInterruptedException() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new InterruptedException("Request interrupted"));

        assertThatThrownBy(() -> provider.generate("test", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Image generation failed");
    }

    @Test
    void generate_throws_whenResponseBodyIsInvalidJson() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("not valid json{{{");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        assertThatThrownBy(() -> provider.generate("test", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Image generation failed");
    }

    // ── Edge cases ──

    @Test
    void generate_returnsLargeImageBytes_whenB64IsLarge() throws Exception {
        byte[] imageBytes = new byte[1024 * 100];
        for (int i = 0; i < imageBytes.length; i++) {
            imageBytes[i] = (byte) (i % 256);
        }
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        byte[] result = provider.generate("large image", null);
        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_throws_whenB64IsInvalidBase64() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"b64_json\":\"!!!invalid-base64!!!\"}]}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(response);

        assertThatThrownBy(() -> provider.generate("test", null))
            .isInstanceOf(RuntimeException.class);
    }

    // ── Helper ──

    private OpenAiImageGenProvider createProviderWithMockedClient() {
        HttpClient.Builder mockBuilder = mock(HttpClient.Builder.class);
        when(mockBuilder.connectTimeout(any(Duration.class))).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(mockHttpClient);

        try (MockedStatic<HttpClient> staticMock = mockStatic(HttpClient.class)) {
            staticMock.when(HttpClient::newBuilder).thenReturn(mockBuilder);
            return new OpenAiImageGenProvider(properties);
        }
    }
}