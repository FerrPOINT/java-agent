package com.azhukov.agent.service.transcription;

import com.azhukov.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * OpenAI Whisper transcription provider.
 * Sends audio to the OpenAI audio/transcriptions endpoint.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "agent.transcription.enabled", havingValue = "true")
public class OpenAiTranscriptionProvider implements TranscriptionProvider {

    private final AgentProperties properties;
    private final RestClient restClient;

    public OpenAiTranscriptionProvider(AgentProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
            .baseUrl("https://api.openai.com/v1")
            .build();
    }

    @Override
    public String transcribe(byte[] audioFile) {
        if (audioFile == null || audioFile.length == 0) {
            throw new IllegalArgumentException("Audio file is empty");
        }

        try {
            String apiKey = properties.getTranscription().getApiKey();
            String model = properties.getTranscription().getModel();
            if (model == null || model.isBlank()) {
                model = "whisper-1";
            }

            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("model", model);
            builder.part("file", new ByteArrayResource(audioFile) {
                @Override
                public String getFilename() {
                    return "audio.ogg";
                }
            }, MediaType.parseMediaType("audio/ogg"));

            MultiValueMap<String, org.springframework.http.HttpEntity<?>> parts = builder.build();

            String responseJson = restClient.post()
                .uri("/audio/transcriptions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(String.class);

            if (responseJson == null || responseJson.isBlank()) {
                throw new RuntimeException("Transcription returned empty response");
            }

            // Parse the JSON response to extract the text field
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(responseJson);
            String text = node.path("text").asText("");
            log.debug("Transcribed audio ({} bytes) → {} chars", audioFile.length, text.length());
            return text;
        } catch (Exception e) {
            log.error("Transcription failed: {}", e.getMessage(), e);
            throw new RuntimeException("Transcription failed: " + e.getMessage(), e);
        }
    }
}