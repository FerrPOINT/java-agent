package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.transcription.TranscriptionService;
import com.azhukov.agent.service.tts.TtsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AudioDashboardControllerTest {

    @TempDir
    private Path tempDir;

    private MockMvc mockMvc;
    private AgentProperties properties;

    @Mock
    private TtsService ttsService;
    @Mock
    private TranscriptionService transcriptionService;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getProfile().setName("default");
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        mockMvc = MockMvcBuilders.standaloneSetup(new AudioDashboardController(
                ttsService, transcriptionService, properties))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void speakReturnsHermesDataUrlEnvelope() throws Exception {
        when(ttsService.synthesize("Read this", "nova")).thenReturn(new byte[]{0, 1, (byte) 255});

        mockMvc.perform(post("/api/audio/speak")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "text": "  Read this  ",
                      "voice": "nova"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.data_url").value("data:audio/mpeg;base64,AAH/"))
            .andExpect(jsonPath("$.mime_type").value("audio/mpeg"))
            .andExpect(jsonPath("$.provider").value("edge"));
    }

    @Test
    void speakRejectsBlankTextWithoutCallingProvider() throws Exception {
        mockMvc.perform(post("/api/audio/speak")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Text is required"));

        verify(ttsService, never()).synthesize(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void transcribeDecodesDataUrlAndReturnsTranscriptAndTextAliases() throws Exception {
        byte[] audio = new byte[]{1, 2, 3};
        String dataUrl = "data:audio/webm;codecs=opus;base64," + Base64.getEncoder().encodeToString(audio);
        when(transcriptionService.transcribe(
                org.mockito.ArgumentMatchers.any(byte[].class),
                eq("recording.webm"),
                eq("audio/webm")))
            .thenReturn("  heard this  ");

        mockMvc.perform(post("/api/audio/transcribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "data_url": "%s",
                      "mime_type": "audio/webm;codecs=opus"
                    }
                    """.formatted(dataUrl)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.transcript").value("heard this"))
            .andExpect(jsonPath("$.text").value("heard this"))
            .andExpect(jsonPath("$.provider").value("openai"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(transcriptionService).transcribe(bytes.capture(), eq("recording.webm"), eq("audio/webm"));
        assertThat(bytes.getValue()).containsExactly(audio);
    }

    @Test
    void transcribeRejectsInvalidDataUrl() throws Exception {
        mockMvc.perform(post("/api/audio/transcribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data_url\":\"not-a-data-url\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Invalid audio payload"));
    }

    @Test
    void transcribeRejectsNonAudioMime() throws Exception {
        mockMvc.perform(post("/api/audio/transcribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data_url\":\"data:text/plain;base64,AA==\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Payload must be an audio recording"));
    }

    @Test
    void voiceConfigDefaultsToRelayWithoutSecrets() throws Exception {
        mockMvc.perform(get("/api/audio/voice-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.stt.mode").value("relay"))
            .andExpect(jsonPath("$.stt.reason").value("transcription disabled"))
            .andExpect(jsonPath("$.tts.mode").value("relay"))
            .andExpect(jsonPath("$.tts.reason").value("tts disabled"));
    }

    @Test
    void voiceConfigReturnsClientDirectOpenAiWhenConfigured() throws Exception {
        properties.getTranscription().setEnabled(true);
        properties.getTranscription().setProvider("openai");
        properties.getTranscription().setApiKey("stt-key");
        properties.getTranscription().setModel("gpt-4o-mini-transcribe");
        properties.getTts().setEnabled(true);
        properties.getTts().setProvider("openai");
        properties.getTts().setApiKey("tts-key");
        properties.getTts().setModel("gpt-4o-mini-tts");
        properties.getTts().setVoice("nova");
        properties.getModel().setBaseUrl("https://proxy.example/v1");

        mockMvc.perform(get("/api/audio/voice-config").param("profile", "worker"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.stt.mode").value("direct"))
            .andExpect(jsonPath("$.stt.wire").value("openai-multipart"))
            .andExpect(jsonPath("$.stt.api_key").value("stt-key"))
            .andExpect(jsonPath("$.stt.model").value("gpt-4o-mini-transcribe"))
            .andExpect(jsonPath("$.tts.mode").value("direct"))
            .andExpect(jsonPath("$.tts.wire").value("openai-speech"))
            .andExpect(jsonPath("$.tts.base_url").value("https://proxy.example/v1"))
            .andExpect(jsonPath("$.tts.api_key").value("tts-key"))
            .andExpect(jsonPath("$.tts.voice").value("nova"));
    }

    @Test
    void elevenLabsVoicesReturnsUnavailableUntilProviderExists() throws Exception {
        mockMvc.perform(get("/api/audio/elevenlabs/voices"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.voices").isArray())
            .andExpect(jsonPath("$.voices.length()").value(0));
    }

    @Test
    void audioRelayEndpointsRejectUnknownProfileLikeHermes() throws Exception {
        String dataUrl = "data:audio/webm;base64," + Base64.getEncoder().encodeToString(new byte[]{1});

        mockMvc.perform(post("/api/audio/transcribe")
                .param("profile", "ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data_url\":\"%s\"}".formatted(dataUrl)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Profile 'ghost' does not exist."));

        mockMvc.perform(post("/api/audio/speak")
                .param("profile", "ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"x\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Profile 'ghost' does not exist."));

        mockMvc.perform(get("/api/audio/elevenlabs/voices").param("profile", "ghost"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Profile 'ghost' does not exist."));

        verify(transcriptionService, never()).transcribe(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
        verify(ttsService, never()).synthesize(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void audioRelayEndpointsAcceptExistingProfileQuery() throws Exception {
        Files.createDirectories(tempDir.resolve("profiles").resolve("worker"));
        when(ttsService.synthesize("Read this", null)).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(post("/api/audio/speak")
                .param("profile", "worker")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Read this\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }
}
