package com.azhukov.agent.service.tts;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI TTS provider using the OpenAI Audio API (tts-1 / tts-1-hd models).
 * Requires {@code agent.tts.api-key} to be set.
 * <p>
 * Voices: alloy, echo, fable, onyx, nova, shimmer.
 * Output format: MP3 (default).
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "agent.tts.enabled", havingValue = "true")
public class OpenAiTtsProvider implements TtsProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String defaultVoice;

    public OpenAiTtsProvider(AgentProperties properties) {
        AgentProperties.TtsProperties tts = properties.getTts();
        this.apiKey = tts.getApiKey();
        this.baseUrl = properties.getModel().getBaseUrl();
        this.model = tts.getModel() != null && !tts.getModel().isBlank()
            ? tts.getModel()
            : "gpt-4o-mini-tts";
        this.defaultVoice = tts.getVoice() != null && !tts.getVoice().isBlank() ? tts.getVoice() : "alloy";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public byte[] synthesize(String text, String voice) {
        return synthesize(text, voice, null, null);
    }

    @Override
    public byte[] synthesize(String text, String voice, Double speed, String instructions) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI TTS requires agent.tts.api-key to be set");
        }
        String usedVoice = (voice != null && !voice.isBlank()) ? voice : defaultVoice;
        double usedSpeed = speed == null ? 1.0 : Math.clamp(speed, 0.25, 4.0);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", text);
            body.put("voice", usedVoice);
            body.put("speed", usedSpeed);
            body.put("response_format", "mp3");
            if (instructions != null && !instructions.isBlank()) {
                body.put("instructions", instructions);
            }
            String jsonBody = MAPPER.writeValueAsString(body);

            String endpoint = baseUrl.endsWith("/")
                ? baseUrl + "audio/speech"
                : baseUrl + "/audio/speech";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body() != null ? response.body() : new byte[0], StandardCharsets.UTF_8);
                log.error("OpenAI TTS failed: status={}, body={}", response.statusCode(), errBody);
                throw new RuntimeException("OpenAI TTS failed: HTTP " + response.statusCode());
            }

            byte[] audio = response.body();
            if (audio == null || audio.length == 0) {
                throw new RuntimeException("OpenAI TTS returned empty audio data");
            }
            log.debug("OpenAI TTS synthesized {} bytes for voice={}", audio.length, usedVoice);
            return audio;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI TTS error: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI TTS failed: " + e.getMessage(), e);
        }
    }
}
