package com.azhukov.agent.service.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Free Edge TTS provider using Microsoft Edge's TTS API.
 * No API key needed — uses the public edge-tts endpoint.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "agent.tts.enabled", havingValue = "true")
public class EdgeTtsProvider implements TtsProvider {

    private final HttpClient httpClient;
    private final String defaultVoice;

    public EdgeTtsProvider(com.azhukov.agent.config.AgentProperties properties) {
        this.defaultVoice = properties.getTts().getVoice();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public byte[] synthesize(String text, String voice) {
        String usedVoice = (voice != null && !voice.isBlank()) ? voice : defaultVoice;
        if (usedVoice == null || usedVoice.isBlank()) {
            usedVoice = "en-US-AriaNeural";
        }
        try {
            // Use the SSML format expected by Edge TTS
            String ssml = buildSsml(text, usedVoice);
            String url = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D46EC9AA29";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
                .header("User-Agent", "Mozilla/5.0")
                .POST(HttpRequest.BodyPublishers.ofString(ssml, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.error("Edge TTS failed: status={}", response.statusCode());
                throw new RuntimeException("TTS failed: HTTP " + response.statusCode());
            }

            byte[] audio = response.body();
            if (audio == null || audio.length == 0) {
                throw new RuntimeException("TTS returned empty audio data");
            }
            log.debug("Edge TTS synthesized {} bytes for voice={}", audio.length, usedVoice);
            return audio;
        } catch (Exception e) {
            log.error("Edge TTS error: {}", e.getMessage(), e);
            throw new RuntimeException("TTS failed: " + e.getMessage(), e);
        }
    }

    private String buildSsml(String text, String voice) {
        return String.format(
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
            + "<voice name='%s'>%s</voice></speak>",
            escapeXml(voice), escapeXml(text)
        );
    }

    private String escapeXml(String s) {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&apos;")
            .replace("\"", "&quot;");
    }
}