package com.azhukov.agent.service.transcription;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI Whisper transcription provider.
 * Sends audio to the OpenAI audio/transcriptions endpoint.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "agent.transcription.enabled", havingValue = "true")
public class OpenAiTranscriptionProvider implements TranscriptionProvider {

    private static final int MAX_FILE_SIZE_BYTES = 25 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SUPPORTED_FORMATS = Set.of(
        ".mp3", ".mp4", ".mpeg", ".mpga", ".m4a", ".wav", ".webm",
        ".ogg", ".oga", ".opus", ".aac", ".flac", ".caf"
    );
    private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.ofEntries(
        Map.entry("audio/mpeg", ".mp3"),
        Map.entry("audio/mp3", ".mp3"),
        Map.entry("audio/mp4", ".m4a"),
        Map.entry("video/mp4", ".mp4"),
        Map.entry("audio/x-m4a", ".m4a"),
        Map.entry("audio/wav", ".wav"),
        Map.entry("audio/x-wav", ".wav"),
        Map.entry("audio/webm", ".webm"),
        Map.entry("audio/ogg", ".ogg"),
        Map.entry("audio/opus", ".opus"),
        Map.entry("audio/aac", ".aac"),
        Map.entry("audio/flac", ".flac"),
        Map.entry("audio/x-caf", ".caf")
    );
    private static final Map<String, String> EXTENSION_CONTENT_TYPES = Map.ofEntries(
        Map.entry(".mp3", "audio/mpeg"),
        Map.entry(".mpeg", "audio/mpeg"),
        Map.entry(".mpga", "audio/mpeg"),
        Map.entry(".mp4", "audio/mp4"),
        Map.entry(".m4a", "audio/mp4"),
        Map.entry(".wav", "audio/wav"),
        Map.entry(".webm", "audio/webm"),
        Map.entry(".ogg", "audio/ogg"),
        Map.entry(".oga", "audio/ogg"),
        Map.entry(".opus", "audio/ogg"),
        Map.entry(".aac", "audio/aac"),
        Map.entry(".flac", "audio/flac"),
        Map.entry(".caf", "audio/x-caf")
    );

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
        return transcribe(audioFile, "audio.ogg", "audio/ogg");
    }

    @Override
    public String transcribe(byte[] audioFile, String filename, String contentType) {
        if (audioFile == null || audioFile.length == 0) {
            throw new IllegalArgumentException("Audio file is empty");
        }
        if (audioFile.length > MAX_FILE_SIZE_BYTES) {
            double sizeMb = audioFile.length / (1024.0 * 1024.0);
            throw new IllegalArgumentException(
                "Audio file too large: %.1fMB (max 25MB)".formatted(sizeMb)
            );
        }
        String safeFilename = resolveFilename(filename, contentType);
        MediaType mediaType = resolveMediaType(safeFilename, contentType);

        String apiKey = properties.getTranscription().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI transcription requires agent.transcription.api-key to be set");
        }

        try {
            String model = properties.getTranscription().getModel();
            if (model == null || model.isBlank()) {
                model = "whisper-1";
            }

            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("model", model);
            builder.part("file", new ByteArrayResource(audioFile) {
                @Override
                public String getFilename() {
                    return safeFilename;
                }
            }, mediaType);

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
            var node = MAPPER.readTree(responseJson);
            String text = node.path("text").asText("");
            log.debug("Transcribed audio ({} bytes) → {} chars", audioFile.length, text.length());
            return text;
        } catch (Exception e) {
            log.error("Transcription failed: {}", e.getMessage(), e);
            throw new RuntimeException("Transcription failed: " + e.getMessage(), e);
        }
    }

    private String resolveFilename(String filename, String contentType) {
        String name = filename == null ? "" : filename.replace('\\', '/').trim();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}\"']", "_").trim();
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            name = "audio";
        }

        String extension = extensionOf(name);
        if (!extension.isBlank()) {
            if (!SUPPORTED_FORMATS.contains(extension)) {
                throw new IllegalArgumentException(
                    "Unsupported audio format: " + extension
                        + ". Supported: " + String.join(", ", SUPPORTED_FORMATS.stream().sorted().toList())
                );
            }
            return name;
        }

        String inferred = extensionForContentType(contentType);
        if (inferred == null) {
            inferred = ".ogg";
        }
        return name + inferred;
    }

    private MediaType resolveMediaType(String filename, String contentType) {
        String extension = extensionOf(filename);
        if (contentType != null && !contentType.isBlank()) {
            try {
                MediaType parsed = MediaType.parseMediaType(contentType);
                if ("audio".equalsIgnoreCase(parsed.getType()) || "video".equalsIgnoreCase(parsed.getType())) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // Fall back to the extension mapping below.
            }
        }
        return MediaType.parseMediaType(
            EXTENSION_CONTENT_TYPES.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM_VALUE)
        );
    }

    private String extensionForContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            String normalized = parsed.getType().toLowerCase(Locale.ROOT)
                + "/" + parsed.getSubtype().toLowerCase(Locale.ROOT);
            return CONTENT_TYPE_EXTENSIONS.get(normalized);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
