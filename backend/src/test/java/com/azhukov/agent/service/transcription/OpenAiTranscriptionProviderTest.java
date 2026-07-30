package com.azhukov.agent.service.transcription;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Unit tests for {@link OpenAiTranscriptionProvider}.
 * Uses {@link MockRestServiceServer} to mock the internally-created {@link RestClient}.
 */
class OpenAiTranscriptionProviderTest {

    private AgentProperties properties;
    private OpenAiTranscriptionProvider provider;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AgentProperties();
        properties.getTranscription().setEnabled(true);
        properties.getTranscription().setApiKey("test-api-key");
        properties.getTranscription().setModel("whisper-1");

        provider = new OpenAiTranscriptionProvider(properties);

        // Build a RestClient backed by MockRestServiceServer and inject it
        RestClient.Builder builder = RestClient.builder()
            .baseUrl("https://api.openai.com/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient mockRestClient = builder.build();

        Field field = OpenAiTranscriptionProvider.class.getDeclaredField("restClient");
        field.setAccessible(true);
        field.set(provider, mockRestClient);
    }

    @Test
    void transcribe_success_returnsText() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andRespond(withSuccess("{\"text\":\"Hello, this is a test.\"}", MediaType.APPLICATION_JSON));

        String result = provider.transcribe("audio-data".getBytes());

        assertThat(result).isEqualTo("Hello, this is a test.");
        server.verify();
    }

    @Test
    void transcribe_usesDefaultModelWhenModelIsBlank() {
        properties.getTranscription().setModel("");

        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
            .andRespond(withSuccess("{\"text\":\"Default model result.\"}", MediaType.APPLICATION_JSON));

        String result = provider.transcribe("audio".getBytes());

        assertThat(result).isEqualTo("Default model result.");
        server.verify();
    }

    @Test
    void transcribe_usesDefaultModelWhenModelIsNull() {
        properties.getTranscription().setModel(null);

        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
            .andRespond(withSuccess("{\"text\":\"Null model result.\"}", MediaType.APPLICATION_JSON));

        String result = provider.transcribe("audio".getBytes());

        assertThat(result).isEqualTo("Null model result.");
        server.verify();
    }

    @Test
    void transcribe_nullAudio_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> provider.transcribe(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Audio file is empty");
    }

    @Test
    void transcribe_emptyAudio_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> provider.transcribe(new byte[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Audio file is empty");
    }

    @Test
    void transcribe_emptyResponseBody_throwsRuntimeException() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.transcribe("audio".getBytes()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("empty response");
        server.verify();
    }

    @Test
    void transcribe_nullResponseBody_throwsRuntimeException() {
        // When the server returns no body, RestClient returns null for String.class
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
            .andRespond(withSuccess());

        assertThatThrownBy(() -> provider.transcribe("audio".getBytes()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("empty response");
        server.verify();
    }

    @Test
    void transcribe_httpError500_throwsRuntimeException() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
            .andRespond(withServerError().body("Internal Server Error"));

        assertThatThrownBy(() -> provider.transcribe("audio".getBytes()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Transcription failed");
        server.verify();
    }

    @Test
    void transcribe_httpError401_throwsRuntimeException() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
            .andRespond(withUnauthorizedRequest().body("Unauthorized"));

        assertThatThrownBy(() -> provider.transcribe("audio".getBytes()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Transcription failed");
        server.verify();
    }

    @Test
    void transcribe_missingTextField_returnsEmptyString() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
            .andRespond(withSuccess("{\"language\":\"en\"}", MediaType.APPLICATION_JSON));

        // When "text" field is missing, node.path("text").asText("") returns ""
        String result = provider.transcribe("audio".getBytes());

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void transcribe_invalidJsonResponse_throwsRuntimeException() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
            .andRespond(withSuccess("not valid json{{{", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.transcribe("audio".getBytes()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Transcription failed");
        server.verify();
    }

    @Test
    void transcribe_singleByteAudio_succeeds() {
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
            .andRespond(withSuccess("{\"text\":\"Hi\"}", MediaType.APPLICATION_JSON));

        String result = provider.transcribe(new byte[]{42});

        assertThat(result).isEqualTo("Hi");
        server.verify();
    }
}