package com.azhukov.agent.service.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
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
    private final String edgeTtsCommand;

    public EdgeTtsProvider(
        com.azhukov.agent.config.AgentProperties properties,
        @Value("${agent.tts.edge.command:}") String edgeTtsCommand
    ) {
        this.defaultVoice = properties.getTts().getVoice();
        this.edgeTtsCommand = edgeTtsCommand == null ? "" : edgeTtsCommand.trim();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public String name() {
        return "edge";
    }

    @Override
    public byte[] synthesize(String text, String voice) {
        String usedVoice = (voice != null && !voice.isBlank()) ? voice : defaultVoice;
        if (usedVoice == null || usedVoice.isBlank()) {
            usedVoice = "en-US-AriaNeural";
        }
        if (!edgeTtsCommand.isBlank()) {
            return synthesizeWithEdgeTtsCli(text, usedVoice);
        }
        try {
            // Legacy HTTP endpoint retained only for deployments that have not
            // configured the maintained edge-tts client command.
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

    /**
     * Uses the maintained edge-tts client, which speaks the current signed
     * WebSocket protocol. The old public HTTP URL now returns 404, so keeping
     * its implementation as the only path made the advertised default broken.
     */
    private byte[] synthesizeWithEdgeTtsCli(String text, String voice) {
        Path audioFile = null;
        Path errorFile = null;
        try {
            // Redirect both pipes before waitFor: an MP3 can fill a process
            // pipe, otherwise a successful synthesis deadlocks at 45 seconds.
            audioFile = java.nio.file.Files.createTempFile("edge-tts-", ".mp3");
            errorFile = java.nio.file.Files.createTempFile("edge-tts-", ".err");
            Process process = new ProcessBuilder(edgeTtsCommand, "--text", text, "--voice", voice)
                .redirectOutput(audioFile.toFile())
                .redirectError(errorFile.toFile())
                .start();
            boolean completed = process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("edge-tts timed out after 45 seconds");
            }
            byte[] audio = java.nio.file.Files.readAllBytes(audioFile);
            byte[] stderr = java.nio.file.Files.readAllBytes(errorFile);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("edge-tts exited " + process.exitValue()
                    + (stderr.length == 0 ? "" : ": " + new String(stderr, StandardCharsets.UTF_8).trim()));
            }
            if (audio.length == 0) {
                throw new IllegalStateException("edge-tts returned empty audio data");
            }
            log.debug("Edge TTS CLI synthesized {} bytes for voice={}", audio.length, voice);
            return audio;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("edge-tts interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("edge-tts command failed to start: " + e.getMessage(), e);
        } finally {
            deleteTempFile(audioFile);
            deleteTempFile(errorFile);
        }
    }

    private static void deleteTempFile(Path path) {
        if (path == null) return;
        try {
            java.nio.file.Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary provider output is best-effort cleanup.
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