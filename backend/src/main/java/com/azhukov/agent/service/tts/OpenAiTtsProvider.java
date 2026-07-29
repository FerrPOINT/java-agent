package com.azhukov.agent.service.tts;

import com.azhukov.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String defaultVoice;

    public OpenAiTtsProvider(AgentProperties properties) {
        AgentProperties.TtsProperties tts = properties.getTts();
        this.apiKey = tts.getApiKey();
        this.baseUrl = properties.getModel().getBaseUrl();
        this.model = "tts-1";
        this.defaultVoice = tts.getVoice() != null && !tts.getVoice().isBlank() ? tts.getVoice() : "alloy";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public byte[] synthesize(String text, String voice) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI TTS requires agent.tts.api-key to be set");
        }
        String usedVoice = (voice != null && !voice.isBlank()) ? voice : defaultVoice;
        try {
            String body = String.format(
                "{\"model\":\"%s\",\"input\":\"%s\",\"voice\":\"%s\",\"format\":\"mp3\"}",
                model, escapeJson(text), escapeJson(usedVoice)
            );

            String endpoint = baseUrl.endsWith("/")
                ? baseUrl + "audio/speech"
                : baseUrl + "/audio/speech";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
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

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}