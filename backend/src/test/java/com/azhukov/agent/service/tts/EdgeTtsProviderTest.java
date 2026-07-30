package com.azhukov.agent.service.tts;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EdgeTtsProvider}.
 * Mocks the internally-created {@link HttpClient} via reflection.
 */
@ExtendWith(MockitoExtension.class)
class EdgeTtsProviderTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<byte[]> httpResponse;

    private AgentProperties properties;
    private EdgeTtsProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AgentProperties();
        properties.getTts().setEnabled(true);
        properties.getTts().setVoice("en-US-AriaNeural");

        provider = new EdgeTtsProvider(properties);
        injectField("httpClient", httpClient);
    }

    private void injectField(String fieldName, Object value) throws Exception {
        Field field = EdgeTtsProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(provider, value);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int statusCode, byte[] body) {
        when(httpResponse.statusCode()).thenReturn(statusCode);
        when(httpResponse.body()).thenReturn(body);
        try {
            doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void synthesize_success_returnsAudioBytes() throws Exception {
        byte[] audio = {1, 2, 3, 4, 5};
        stubResponse(200, audio);

        byte[] result = provider.synthesize("Hello world", null);

        assertThat(result).isEqualTo(audio);
    }

    @Test
    void synthesize_explicitVoice_succeeds() throws Exception {
        byte[] audio = {10, 20, 30};
        stubResponse(200, audio);

        byte[] result = provider.synthesize("Hello world", "en-US-GuyNeural");

        assertThat(result).isEqualTo(audio);
    }

    @Test
    void synthesize_nullVoice_usesDefaultVoice() throws Exception {
        byte[] audio = {1};
        stubResponse(200, audio);

        byte[] result = provider.synthesize("Hello world", null);

        assertThat(result).isEqualTo(audio);
    }

    @Test
    void synthesize_blankVoice_usesDefaultVoice() throws Exception {
        byte[] audio = {1};
        stubResponse(200, audio);

        byte[] result = provider.synthesize("Hello world", "  ");

        assertThat(result).isEqualTo(audio);
    }

    @Test
    void synthesize_nullDefaultVoiceAndNullProvidedVoice_usesFallback() throws Exception {
        properties.getTts().setVoice(null);

        byte[] audio = {1, 2, 3};
        stubResponse(200, audio);

        byte[] result = provider.synthesize("Hello world", null);

        assertThat(result).isEqualTo(audio);
    }

    @Test
    void synthesize_blankDefaultVoiceAndBlankProvidedVoice_usesFallback() throws Exception {
        properties.getTts().setVoice("");

        byte[] audio = {1, 2, 3};
        stubResponse(200, audio);

        byte[] result = provider.synthesize("Hello world", "");

        assertThat(result).isEqualTo(audio);
    }

    @Test
    void synthesize_httpError500_throwsRuntimeException() {
        when(httpResponse.statusCode()).thenReturn(500);
        try {
            doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThatThrownBy(() -> provider.synthesize("Hello world", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP 500");
    }

    @Test
    void synthesize_httpError403_throwsRuntimeException() {
        when(httpResponse.statusCode()).thenReturn(403);
        try {
            doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThatThrownBy(() -> provider.synthesize("Hello world", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP 403");
    }

    @Test
    void synthesize_emptyAudioBody_throwsRuntimeException() {
        stubResponse(200, new byte[0]);

        assertThatThrownBy(() -> provider.synthesize("Hello world", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("empty audio");
    }

    @Test
    void synthesize_nullAudioBody_throwsRuntimeException() {
        stubResponse(200, null);

        assertThatThrownBy(() -> provider.synthesize("Hello world", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("empty audio");
    }

    @Test
    void synthesize_httpClientThrowsIOException_wrapsInRuntimeException() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("Network unreachable"));

        assertThatThrownBy(() -> provider.synthesize("Hello world", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Network unreachable");
    }

    @Test
    void synthesize_httpClientThrowsInterruptedException_wrapsInRuntimeException() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new InterruptedException("Thread interrupted"));

        assertThatThrownBy(() -> provider.synthesize("Hello world", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Thread interrupted");
    }

    @Test
    void synthesize_emptyText_stillSendsRequest() throws Exception {
        byte[] audio = {1, 2, 3};
        stubResponse(200, audio);

        // Empty text is not validated by the provider
        byte[] result = provider.synthesize("", null);

        assertThat(result).isEqualTo(audio);
    }

    @Test
    void synthesize_textWithSpecialChars_succeeds() throws Exception {
        byte[] audio = {1, 2, 3, 4};
        stubResponse(200, audio);

        // Text with XML special characters that need escaping
        byte[] result = provider.synthesize("Hello <world> & 'friends' \"quoted\"", null);

        assertThat(result).isEqualTo(audio);
    }
}