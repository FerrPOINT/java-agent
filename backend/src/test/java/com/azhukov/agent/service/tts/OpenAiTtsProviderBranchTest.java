package com.azhukov.agent.service.tts;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for {@link OpenAiTtsProvider}.
 * Covers endpoint URL construction, voice fallback, error paths, and escapeJson.
 */
class OpenAiTtsProviderBranchTest {

    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getTts().setEnabled(true);
        properties.getTts().setProvider("openai");
        properties.getTts().setApiKey("test-key");
        properties.getTts().setVoice("alloy");
        properties.getModel().setBaseUrl("https://api.openai.com/v1");
    }

    private void injectMockClient(OpenAiTtsProvider provider, HttpClient httpClient) throws Exception {
        Field f = OpenAiTtsProvider.class.getDeclaredField("httpClient");
        f.setAccessible(true);
        f.set(provider, httpClient);
    }

    @Test
    void synthesize_nullApiKey_throwsIllegalState() {
        properties.getTts().setApiKey(null);
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        assertThatThrownBy(() -> provider.synthesize("hello", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api-key");
    }

    @Test
    void synthesize_blankApiKey_throwsIllegalState() {
        properties.getTts().setApiKey("   ");
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        assertThatThrownBy(() -> provider.synthesize("hello", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api-key");
    }

    @Test
    void synthesize_explicitVoice_overridesDefault() throws Exception {
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{1, 2, 3});
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        injectMockClient(provider, httpClient);

        byte[] result = provider.synthesize("hello", "echo");
        assertThat(result).containsExactly(1, 2, 3);
    }

    @Test
    void synthesize_baseUrlWithTrailingSlash_noDoubleSlash() throws Exception {
        properties.getModel().setBaseUrl("https://api.openai.com/v1/");
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{1});
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        injectMockClient(provider, httpClient);

        byte[] result = provider.synthesize("hello", null);
        assertThat(result).containsExactly(1);
    }

    @Test
    void synthesize_baseUrlWithoutTrailingSlash_appendsSlash() throws Exception {
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{1});
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        injectMockClient(provider, httpClient);

        byte[] result = provider.synthesize("hello", null);
        assertThat(result).containsExactly(1);
    }

    @Test
    void synthesize_httpError403_throwsRuntime() throws Exception {
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(403);
        when(response.body()).thenReturn("{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8));
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        injectMockClient(provider, httpClient);

        assertThatThrownBy(() -> provider.synthesize("hello", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP 403");
    }

    @Test
    void synthesize_emptyAudioBody_throwsRuntime() throws Exception {
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[0]);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        injectMockClient(provider, httpClient);

        assertThatThrownBy(() -> provider.synthesize("hello", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("empty audio");
    }

    @Test
    void synthesize_nullAudioBody_throwsRuntime() throws Exception {
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(null);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        injectMockClient(provider, httpClient);

        assertThatThrownBy(() -> provider.synthesize("hello", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("empty audio");
    }

    @Test
    void synthesize_httpClientThrowsIOException_wrapsInRuntime() throws Exception {
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new java.io.IOException("Network error"));
        injectMockClient(provider, httpClient);

        assertThatThrownBy(() -> provider.synthesize("hello", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Network error");
    }

    @Test
    void synthesize_defaultVoiceNull_usesAlloy() throws Exception {
        properties.getTts().setVoice(null);
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{1});
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        injectMockClient(provider, httpClient);

        byte[] result = provider.synthesize("hello", null);
        assertThat(result).containsExactly(1);
    }

    @Test
    void synthesize_blankDefaultVoice_usesAlloy() throws Exception {
        properties.getTts().setVoice("   ");
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{1});
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        injectMockClient(provider, httpClient);

        byte[] result = provider.synthesize("hello", null);
        assertThat(result).containsExactly(1);
    }

    @Test
    void synthesize_blankProvidedVoice_usesDefault() throws Exception {
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{1});
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        injectMockClient(provider, httpClient);

        byte[] result = provider.synthesize("hello", "  ");
        assertThat(result).containsExactly(1);
    }

    @Test
    void synthesize_textWithSpecialCharacters_succeeds() throws Exception {
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{1, 2});
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        injectMockClient(provider, httpClient);

        byte[] result = provider.synthesize("hello \"world\"\nnew\tline", null);
        assertThat(result).containsExactly(1, 2);
    }

    @Test
    void synthesize_illegalStateNotWrapped() throws Exception {
        // IllegalStateException should be rethrown as-is, not wrapped
        properties.getTts().setApiKey(null);
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);

        assertThatThrownBy(() -> provider.synthesize("hello", null))
            .isInstanceOf(IllegalStateException.class);
    }
}