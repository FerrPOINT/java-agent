package com.azhukov.agent.service.imagegen;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenAiImageGenProvider}.
 * Mocks the internally-created {@link HttpClient} via reflection.
 */
@ExtendWith(MockitoExtension.class)
class OpenAiImageGenProviderTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private AgentProperties properties;
    private OpenAiImageGenProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AgentProperties();
        properties.getImageGen().setEnabled(true);
        properties.getImageGen().setApiKey("test-api-key");
        properties.getImageGen().setModel("dall-e-3");

        provider = new OpenAiImageGenProvider(properties);
        injectField("httpClient", httpClient);
    }

    private void injectField(String fieldName, Object value) throws Exception {
        Field field = OpenAiImageGenProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(provider, value);
    }

    private void stubResponse(int statusCode, String body) {
        when(httpResponse.statusCode()).thenReturn(statusCode);
        when(httpResponse.body()).thenReturn(body);
        try {
            doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void generate_success_returnsDecodedImageBytes() throws Exception {
        byte[] imageBytes = {1, 2, 3, 4, 5};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseJson = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        stubResponse(200, responseJson);

        byte[] result = provider.generate("a cat", "1:1");

        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_usesDefaultModelWhenModelIsBlank() throws Exception {
        properties.getImageGen().setModel("");

        byte[] imageBytes = {10, 20, 30};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseJson = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        stubResponse(200, responseJson);

        byte[] result = provider.generate("a dog", null);

        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_usesDefaultModelWhenModelIsNull() throws Exception {
        properties.getImageGen().setModel(null);

        byte[] imageBytes = {10, 20, 30};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseJson = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        stubResponse(200, responseJson);

        byte[] result = provider.generate("a dog", "16:9");

        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_httpError_throwsRuntimeException() {
        stubResponse(500, "Internal Server Error");

        assertThatThrownBy(() -> provider.generate("prompt", "1:1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP 500");
    }

    @Test
    void generate_httpError403_throwsRuntimeException() {
        stubResponse(403, "Forbidden");

        assertThatThrownBy(() -> provider.generate("prompt", "1:1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP 403");
    }

    @Test
    void generate_noDataArray_throwsRuntimeException() {
        stubResponse(200, "{\"error\":\"something\"}");

        assertThatThrownBy(() -> provider.generate("prompt", "1:1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("no data");
    }

    @Test
    void generate_emptyDataArray_throwsRuntimeException() {
        stubResponse(200, "{\"data\":[]}");

        assertThatThrownBy(() -> provider.generate("prompt", "1:1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("no data");
    }

    @Test
    void generate_blankB64Json_throwsRuntimeException() {
        stubResponse(200, "{\"data\":[{\"b64_json\":\"\"}]}");

        assertThatThrownBy(() -> provider.generate("prompt", "1:1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("empty b64_json");
    }

    @Test
    void generate_missingB64JsonField_throwsRuntimeException() {
        stubResponse(200, "{\"data\":[{\"url\":\"https://example.com/image.png\"}]}");

        assertThatThrownBy(() -> provider.generate("prompt", "1:1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("empty b64_json");
    }

    @Test
    void generate_httpClientThrowsIOException_wrapsInRuntimeException() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("Network unreachable"));

        assertThatThrownBy(() -> provider.generate("prompt", "1:1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Network unreachable");
    }

    @Test
    void generate_httpClientThrowsInterruptedException_wrapsInRuntimeException() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new InterruptedException("Thread interrupted"));

        assertThatThrownBy(() -> provider.generate("prompt", "1:1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Thread interrupted");
    }

    @Test
    void generate_nullAspectRatio_succeeds() throws Exception {
        byte[] imageBytes = {1, 2, 3};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseJson = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        stubResponse(200, responseJson);

        byte[] result = provider.generate("prompt", null);

        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_blankAspectRatio_succeeds() throws Exception {
        byte[] imageBytes = {1, 2, 3};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseJson = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        stubResponse(200, responseJson);

        byte[] result = provider.generate("prompt", "  ");

        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_landscapeAspectRatio_succeeds() throws Exception {
        byte[] imageBytes = {1, 2, 3};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseJson = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        stubResponse(200, responseJson);

        byte[] result = provider.generate("prompt", "16:9");

        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_portraitAspectRatio_succeeds() throws Exception {
        byte[] imageBytes = {1, 2, 3};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseJson = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        stubResponse(200, responseJson);

        byte[] result = provider.generate("prompt", "9:16");

        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_unknownAspectRatio_succeedsWithDefaultSize() throws Exception {
        byte[] imageBytes = {1, 2, 3};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseJson = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        stubResponse(200, responseJson);

        byte[] result = provider.generate("prompt", "4:3");

        assertThat(result).isEqualTo(imageBytes);
    }

    @Test
    void generate_invalidJsonResponse_throwsRuntimeException() {
        stubResponse(200, "not valid json{{{");

        assertThatThrownBy(() -> provider.generate("prompt", "1:1"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void generate_emptyPrompt_stillSendsRequest() throws Exception {
        byte[] imageBytes = {1};
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String responseJson = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        stubResponse(200, responseJson);

        // Empty prompt is not validated by the provider — it sends it as-is
        byte[] result = provider.generate("", "1:1");

        assertThat(result).isEqualTo(imageBytes);
    }
}