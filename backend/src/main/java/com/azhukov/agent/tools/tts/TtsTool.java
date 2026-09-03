package com.azhukov.agent.tools.tts;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.tts.SpokenTextNormalizer;
import com.azhukov.agent.service.tts.TtsProvider;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Agent tool for text-to-speech synthesis.
 * Saves generated audio to /tmp/ and returns the file path as MEDIA: path.
 */
@AgentTool(
    name = "text_to_speech",
    description = "Convert text to speech audio. Returns a MEDIA: path that the platform delivers as native audio. Compatible providers render as a voice bubble on Telegram; otherwise audio is sent as a regular attachment. In CLI mode, saves to ~/voice-memos/. Voice and provider are user-configured (built-in providers like edge/openai or custom command providers under tts.providers.<name>), not model-selected.",
    toolset = "tts"
)
@Component
@Slf4j
@RequiredArgsConstructor
public class TtsTool implements ToolHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FALLBACK_MAX_TEXT_LENGTH = 4000;
    private static final Map<String, Integer> PROVIDER_MAX_TEXT_LENGTH = Map.ofEntries(
        Map.entry("edge", 5000),
        Map.entry("openai", 4096),
        Map.entry("xai", 15000),
        Map.entry("minimax", 10000),
        Map.entry("mistral", 4000),
        Map.entry("gemini", 32000),
        Map.entry("elevenlabs", 10000),
        Map.entry("neutts", 2000),
        Map.entry("kittentts", 2000),
        Map.entry("piper", 5000),
        Map.entry("deepinfra", 4000)
    );

    private final List<TtsProvider> providers;
    private final AgentProperties properties;


    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TtsArgs args;
        try {
            args = ToolHandler.parseJson(arguments, TtsArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonErrorResponse(e.getMessage());
        }

        if (args.text() == null || args.text().isBlank()) {
            return jsonErrorResponse("Text is required");
        }

        String spokenText = SpokenTextNormalizer.normalize(args.text());
        if (spokenText.isBlank()) {
            return jsonErrorResponse("Text is empty after TTS cleanup");
        }

        TtsProvider provider = resolveProvider(args.provider());
        if (provider == null) {
            String requested = args.provider() != null && !args.provider().isBlank()
                ? " '" + args.provider() + "'" : "";
            return jsonErrorResponse("TTS provider" + requested + " is not enabled or unavailable.");
        }

        if (args.outputPath() != null && !args.outputPath().isBlank()) {
            ToolResult pathError = validateOutputPath(args.outputPath());
            if (pathError != null) return pathError;
        }

        Double speed = args.speed() == null ? null : Math.clamp(args.speed(), 0.25, 4.0);
        String voice = args.voice() != null ? args.voice() : properties.getTts().getVoice();

        List<String> chunks = splitTextForTts(spokenText, maxTextLength(provider.name()));
        if (chunks.isEmpty()) {
            return jsonErrorResponse("Text is empty after TTS cleanup");
        }

        Path baseOutputPath = resolveOutputPath(args.outputPath());
        List<Path> writtenPaths = new ArrayList<>();
        try {
            for (int i = 0; i < chunks.size(); i++) {
                Path outputPath = chunks.size() == 1
                    ? baseOutputPath
                    : chunkOutputPath(baseOutputPath, i + 1);
                Path parent = outputPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                byte[] audio = provider.synthesize(chunks.get(i), voice, speed, args.instructions());
                if (audio == null || audio.length == 0) {
                    throw new IllegalStateException("TTS provider returned empty audio data");
                }
                Files.write(outputPath, audio);
                writtenPaths.add(outputPath);
            }

            log.debug("TTS generated {} file(s) for provider={}", writtenPaths.size(), provider.name());
            return ToolResult.ok(jsonSuccessResponse(writtenPaths, provider.name(), chunks.size()));
        } catch (Exception e) {
            for (Path path : writtenPaths) {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception cleanupError) {
                    log.debug("Failed to clean partial TTS artifact {}: {}", path, cleanupError.getMessage());
                }
            }
            log.error("TTS failed: {}", e.getMessage(), e);
            return jsonErrorResponse("TTS failed: " + errorMessage(e));
        }
    }

    private ToolResult validateOutputPath(String outputPath) {
        String normalized = outputPath.replace('\\', '/');
        if (normalized.matches(".*(^|/)\\.\\.(/|$).*$")) {
            return jsonErrorResponse("output_path contains '..' traversal component. Use an absolute path or a relative path without '..'.");
        }
        Path resolved = Path.of(outputPath).toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path etc = Path.of("/etc").toAbsolutePath().normalize();
        Path proc = Path.of("/proc").toAbsolutePath().normalize();
        if (resolved.startsWith(home.resolve(".ssh")) || resolved.startsWith(etc) || resolved.startsWith(proc)) {
            return jsonErrorResponse("output_path targets a protected credential or system path. Choose a normal audio output location.");
        }
        return null;
    }

    private ToolResult jsonErrorResponse(String error) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("error", error);
        response.put("success", false);
        return new ToolResult(false, response.toString(), error);
    }

    private String jsonSuccessResponse(List<Path> paths, String provider, int chunkCount) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", true);
        response.put("file_path", paths.getFirst().toString());
        ArrayNode filePaths = response.putArray("file_paths");
        for (Path path : paths) {
            filePaths.add(path.toString());
        }
        response.put("media_tag", mediaTag(paths));
        response.put("provider", provider);
        response.put("voice_compatible", false);
        response.put("chunk_count", chunkCount);
        response.put("delivery_file_count", paths.size());
        response.put("combined_chunks", false);
        return response.toString();
    }

    private String mediaTag(List<Path> paths) {
        return paths.stream()
            .map(path -> "MEDIA:" + path)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
            ? e.getClass().getSimpleName()
            : e.getMessage();
    }

    private Path resolveOutputPath(String outputPath) {
        if (outputPath != null && !outputPath.isBlank()) {
            return Path.of(outputPath).toAbsolutePath().normalize();
        }
        return defaultOutputDir()
            .resolve("tts_" + UUID.randomUUID() + ".mp3")
            .toAbsolutePath()
            .normalize();
    }

    private Path defaultOutputDir() {
        String hermesHome = System.getenv("HERMES_HOME");
        if (hermesHome != null && !hermesHome.isBlank()) {
            return Path.of(hermesHome).toAbsolutePath().normalize().resolve("cache/audio");
        }
        return Path.of(System.getProperty("user.home"), ".hermes", "cache", "audio")
            .toAbsolutePath()
            .normalize();
    }

    private TtsProvider resolveProvider(String requested) {
        String name = requested != null && !requested.isBlank()
            ? requested.trim() : properties.getTts().getProvider();
        return providers.stream()
            .filter(provider -> provider.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    private int maxTextLength(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return FALLBACK_MAX_TEXT_LENGTH;
        }
        return PROVIDER_MAX_TEXT_LENGTH.getOrDefault(
            providerName.toLowerCase(Locale.ROOT).trim(),
            FALLBACK_MAX_TEXT_LENGTH
        );
    }

    private List<String> splitTextForTts(String text, int maxChars) {
        int cap = maxChars > 0 ? maxChars : FALLBACK_MAX_TEXT_LENGTH;
        String normalized = String.join(" ", text.split("\\s+")).trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.length() <= cap) {
            return List.of(normalized);
        }

        String[] sentences = normalized.split("(?<=[.!?;:,])\\s+");
        List<String> expanded = new ArrayList<>();
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= cap) {
                expanded.add(trimmed);
            } else {
                expanded.addAll(splitOversizedSentence(trimmed, cap));
            }
        }

        List<String> chunks = new ArrayList<>();
        String current = "";
        for (String sentence : expanded) {
            String candidate = current.isEmpty() ? sentence : current + " " + sentence;
            if (!current.isEmpty() && candidate.length() > cap) {
                chunks.add(current);
                current = sentence;
            } else {
                current = candidate;
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private List<String> splitOversizedSentence(String sentence, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String current = "";
        for (String word : sentence.split("\\s+")) {
            if (word.length() > maxChars) {
                if (!current.isEmpty()) {
                    chunks.add(current);
                    current = "";
                }
                for (int start = 0; start < word.length(); start += maxChars) {
                    chunks.add(word.substring(start, Math.min(start + maxChars, word.length())));
                }
                continue;
            }
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && candidate.length() > maxChars) {
                chunks.add(current);
                current = word;
            } else {
                current = candidate;
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private Path chunkOutputPath(Path basePath, int index) {
        Path fileName = basePath.getFileName();
        if (fileName == null) {
            return basePath.resolveSibling("tts.chunk%03d.mp3".formatted(index));
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        String chunkName;
        if (dot > 0) {
            chunkName = name.substring(0, dot) + ".chunk%03d".formatted(index) + name.substring(dot);
        } else {
            chunkName = name + ".chunk%03d".formatted(index);
        }
        Path parent = basePath.getParent();
        return parent == null ? Path.of(chunkName) : parent.resolve(chunkName);
    }

    record TtsArgs(
        @ToolParam(description = "The text to convert to speech. Markdown, URLs, emoji, and <think> reasoning blocks are cleaned before synthesis.") String text,
        @ToolParam(description = "Optional custom save path.", required = false) @JsonProperty("output_path") String outputPath,
        @ToolParam(description = "Optional provider-specific voice override.", required = false) @JsonProperty("voice") String voice,
        @ToolParam(description = "Playback speed multiplier. Range: 0.25-4.0.", required = false) @JsonProperty("speed") Double speed,
        @ToolParam(description = "Optional voice-design guidance for providers that support it.", required = false) @JsonProperty("instructions") String instructions,
        @ToolParam(description = "Optional TTS provider override; omitted uses the configured agent.tts.provider.", required = false) @JsonProperty("provider") String provider
    ) {}
}
