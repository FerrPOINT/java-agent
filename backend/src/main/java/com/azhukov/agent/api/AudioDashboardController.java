package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.transcription.TranscriptionService;
import com.azhukov.agent.service.tts.TtsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/audio")
@RequiredArgsConstructor
@Tag(name = "Hermes-compatible", description = "Desktop audio compatibility")
public class AudioDashboardController {

    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Set<String> RESERVED_PROFILE_NAMES = Set.of("hermes", "test", "tmp", "root", "sudo");
    private static final int MAX_TRANSCRIPTION_UPLOAD_BYTES = 25 * 1024 * 1024;
    private static final int MAX_BASE64_TRANSCRIPTION_CHARS =
        ((MAX_TRANSCRIPTION_UPLOAD_BYTES + 2) / 3) * 4 + 16;
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

    private final TtsService ttsService;
    private final TranscriptionService transcriptionService;
    private final AgentProperties properties;

    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, Object>> transcribeAudio(
        @RequestBody(required = false) AudioTranscriptionRequest payload,
        @RequestParam(name = "profile", required = false) String profile
    ) {
        if (payload == null || payload.dataUrl() == null || payload.dataUrl().isBlank()) {
            return badRequest("Invalid audio payload");
        }

        String dataUrl = payload.dataUrl().trim();
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:") || comma < 0) {
            return badRequest("Invalid audio payload");
        }

        String header = dataUrl.substring(0, comma);
        if (!header.toLowerCase(Locale.ROOT).contains(";base64")) {
            return badRequest("Audio payload must be base64 encoded");
        }

        String headerMimeType = headerMimeType(header);
        String mimeType = firstNonBlank(payload.mimeType(), headerMimeType, "audio/webm");
        String normalizedMimeType = normalizedMimeType(mimeType);
        if (!isAudioRecordingMimeType(normalizedMimeType)) {
            return badRequest("Payload must be an audio recording");
        }

        String encoded = dataUrl.substring(comma + 1);
        if (encoded.length() > MAX_BASE64_TRANSCRIPTION_CHARS) {
            return ResponseEntity.status(HttpStatusCode.valueOf(413)).body(errorBody("Audio recording is too large"));
        }

        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return badRequest("Audio payload is not valid base64");
        }

        if (audioBytes.length == 0) {
            return badRequest("Audio recording is empty");
        }
        if (audioBytes.length > MAX_TRANSCRIPTION_UPLOAD_BYTES) {
            return ResponseEntity.status(HttpStatusCode.valueOf(413)).body(errorBody("Audio recording is too large"));
        }
        ResponseEntity<Map<String, Object>> profileValidation = validateKnownProfile(profile);
        if (profileValidation != null) {
            return profileValidation;
        }

        String text;
        try {
            text = transcriptionService.transcribe(
                audioBytes,
                "recording" + audioExtensionForMime(normalizedMimeType),
                normalizedMimeType
            );
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatusCode.valueOf(500))
                .body(errorBody("Transcription failed: " + e.getMessage()));
        }
        if (text == null) {
            return badRequest("Transcription is not enabled");
        }

        String transcript = text.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("transcript", transcript);
        body.put("text", transcript);
        body.put("provider", configuredProvider(properties.getTranscription().getProvider(), "openai"));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/speak")
    public ResponseEntity<Map<String, Object>> speakText(
        @RequestBody(required = false) SpeakRequest payload,
        @RequestParam(name = "profile", required = false) String profile
    ) {
        String text = payload == null || payload.text() == null ? "" : payload.text().trim();
        if (text.isBlank()) {
            return badRequest("Text is required");
        }
        ResponseEntity<Map<String, Object>> profileValidation = validateKnownProfile(profile);
        if (profileValidation != null) {
            return profileValidation;
        }

        byte[] audioBytes;
        try {
            audioBytes = ttsService.synthesize(text, payload.voice());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatusCode.valueOf(500))
                .body(errorBody("Speech synthesis failed: " + e.getMessage()));
        }
        if (audioBytes == null || audioBytes.length == 0) {
            return ResponseEntity.status(HttpStatusCode.valueOf(500))
                .body(errorBody("Speech synthesis failed: empty audio"));
        }

        String encoded = Base64.getEncoder().encodeToString(audioBytes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data_url", "data:audio/mpeg;base64," + encoded);
        body.put("mime_type", "audio/mpeg");
        body.put("provider", configuredProvider(properties.getTts().getProvider(), "edge"));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/voice-config")
    public Map<String, Object> voiceConfig(@RequestParam(name = "profile", required = false) String profile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("stt", sttClientConfig());
        body.put("tts", ttsClientConfig());
        return body;
    }

    @GetMapping("/elevenlabs/voices")
    public ResponseEntity<Map<String, Object>> elevenLabsVoices(@RequestParam(name = "profile", required = false) String profile) {
        ResponseEntity<Map<String, Object>> profileValidation = validateKnownProfile(profile);
        if (profileValidation != null) {
            return profileValidation;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", false);
        body.put("voices", List.of());
        if ("elevenlabs".equals(configuredProvider(properties.getTts().getProvider(), ""))) {
            body.put("error", "unsupported");
        }
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> sttClientConfig() {
        AgentProperties.TranscriptionProperties stt = properties.getTranscription();
        String provider = configuredProvider(stt.getProvider(), "openai");
        String apiKey = firstNonBlank(stt.getApiKey(), "");
        if (stt.isEnabled() && "openai".equals(provider) && !apiKey.isBlank()) {
            Map<String, Object> direct = new LinkedHashMap<>();
            direct.put("mode", "direct");
            direct.put("wire", "openai-multipart");
            direct.put("provider", "openai");
            direct.put("base_url", OPENAI_BASE_URL);
            direct.put("api_key", apiKey);
            direct.put("model", blankToNull(stt.getModel()));
            direct.put("language", null);
            return direct;
        }
        return relayConfig(stt.isEnabled() ? "provider is not client-callable" : "transcription disabled");
    }

    private Map<String, Object> ttsClientConfig() {
        AgentProperties.TtsProperties tts = properties.getTts();
        String provider = configuredProvider(tts.getProvider(), "edge");
        String apiKey = firstNonBlank(tts.getApiKey(), "");
        if (tts.isEnabled() && "openai".equals(provider) && !apiKey.isBlank()) {
            Map<String, Object> direct = new LinkedHashMap<>();
            direct.put("mode", "direct");
            direct.put("wire", "openai-speech");
            direct.put("provider", "openai");
            direct.put("base_url", firstNonBlank(properties.getModel().getBaseUrl(), OPENAI_BASE_URL));
            direct.put("api_key", apiKey);
            direct.put("model", blankToNull(tts.getModel()));
            direct.put("voice", blankToNull(tts.getVoice()));
            direct.put("speed", null);
            return direct;
        }
        return relayConfig(tts.isEnabled() ? "provider is not client-callable" : "tts disabled");
    }

    private static Map<String, Object> relayConfig(String reason) {
        Map<String, Object> relay = new LinkedHashMap<>();
        relay.put("mode", "relay");
        relay.put("reason", reason);
        return relay;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(errorBody(detail));
    }

    private ResponseEntity<Map<String, Object>> validateKnownProfile(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        if (profile == null || "current".equals(profile) || knownProfile(profile)) {
            return null;
        }
        if (!isValidProfileName(profile)) {
            return badRequest("Invalid profile name: " + rawProfile);
        }
        return ResponseEntity.status(HttpStatusCode.valueOf(404))
            .body(errorBody("Profile '" + profile + "' does not exist."));
    }

    private boolean knownProfile(String name) {
        return isValidProfileName(name) && ("default".equals(name)
            || activeProfileName().equals(name)
            || Files.isDirectory(profilePath(name)));
    }

    private String activeProfileName() {
        String name = properties.getProfile() != null ? normalizeProfileName(properties.getProfile().getName()) : null;
        return isValidProfileName(name) ? name : "default";
    }

    private Path profilePath(String name) {
        if ("default".equals(name)) {
            return hermesHome();
        }
        return profilesRoot().resolve(name).toAbsolutePath().normalize();
    }

    private Path profilesRoot() {
        String baseDir = properties.getProfile() != null ? firstNonBlank(properties.getProfile().getBaseDir(), "") : "";
        return baseDir.isBlank()
            ? hermesHome().resolve("profiles").toAbsolutePath().normalize()
            : Path.of(baseDir).toAbsolutePath().normalize();
    }

    private static Path hermesHome() {
        String env = System.getenv("HERMES_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."), ".hermes").toAbsolutePath().normalize();
    }

    private static Map<String, Object> errorBody(String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", detail);
        body.put("error", detail);
        return body;
    }

    private static boolean isAudioRecordingMimeType(String mimeType) {
        return mimeType.startsWith("audio/") || "video/webm".equals(mimeType);
    }

    private static String headerMimeType(String header) {
        String headerValue = header.length() > 5 ? header.substring(5) : "";
        int semi = headerValue.indexOf(';');
        return semi >= 0 ? headerValue.substring(0, semi).trim() : headerValue.trim();
    }

    private static String normalizedMimeType(String mimeType) {
        String normalized = mimeType == null ? "" : mimeType.trim();
        int semi = normalized.indexOf(';');
        if (semi >= 0) {
            normalized = normalized.substring(0, semi);
        }
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private static String audioExtensionForMime(String mimeType) {
        return switch (mimeType) {
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            case "video/mp4" -> ".mp4";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/webm", "video/webm" -> ".webm";
            case "audio/ogg" -> ".ogg";
            case "audio/opus" -> ".opus";
            case "audio/aac" -> ".aac";
            case "audio/flac" -> ".flac";
            case "audio/x-caf" -> ".caf";
            default -> ".ogg";
        };
    }

    private static String firstNonBlank(String first, String second) {
        return firstNonBlank(first, second, "");
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return fallback;
    }

    private static String configuredProvider(String provider, String fallback) {
        String value = firstNonBlank(provider, fallback);
        return value.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean isValidProfileName(String value) {
        return "default".equals(value) || isValidNamedProfileName(value);
    }

    private static boolean isValidNamedProfileName(String value) {
        return value != null && PROFILE_ID.matcher(value).matches() && !RESERVED_PROFILE_NAMES.contains(value);
    }

    private static String normalizeProfileName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record AudioTranscriptionRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("data_url") String dataUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("mime_type") String mimeType
    ) {
    }

    private record SpeakRequest(String text, String voice) {
    }
}
