package com.azhukov.agent.tools.tts;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.tts.TtsProvider;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/**
 * Agent tool for text-to-speech synthesis.
 * Saves generated audio to /tmp/ and returns the file path as MEDIA: path.
 */
@AgentTool(
    name = "text_to_speech",
    description = "Convert text to speech audio. Returns a MEDIA: path that the platform delivers as native audio. Compatible providers render as a voice bubble on Telegram; otherwise audio is sent as a regular attachment. In CLI mode, saves to ~/voice-memos/. Voice and provider are user-configured (built-in providers like edge/openai or custom command providers under tts.providers.<name>), not model-selected.",
    toolset = "core"
)
@Component
@Slf4j
@RequiredArgsConstructor
public class TtsTool implements ToolHandler {

    private final List<TtsProvider> providers;
    private final AgentProperties properties;


    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TtsArgs args = ToolHandler.parseJson(arguments, TtsArgs.class);

        if (args.text() == null || args.text().isBlank()) {
            return ToolResult.fail("text is required");
        }

        if (args.outputPath() != null && !args.outputPath().isBlank()) {
            ToolResult pathError = validateOutputPath(args.outputPath());
            if (pathError != null) return pathError;
        }

        TtsProvider provider = resolveProvider(args.provider());
        if (provider == null) {
            String requested = args.provider() != null && !args.provider().isBlank()
                ? " '" + args.provider() + "'" : "";
            return ToolResult.fail("TTS provider" + requested + " is not enabled or unavailable.");
        }

        Double speed = args.speed() == null ? null : Math.clamp(args.speed(), 0.25, 4.0);
        String voice = args.voice() != null ? args.voice() : properties.getTts().getVoice();

        try {
            byte[] audio = provider.synthesize(args.text(), voice, speed, args.instructions());
            String fileName = "tts_" + UUID.randomUUID() + ".mp3";
            Path outputPath;
            if (args.outputPath() != null && !args.outputPath().isBlank()) {
                outputPath = Path.of(args.outputPath()).toAbsolutePath().normalize();
                Path parent = outputPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } else {
                outputPath = Path.of("/tmp", fileName);
            }
            Files.write(outputPath, audio);
            String result = "MEDIA:" + outputPath.toString() + "\nAudio generated and saved to " + outputPath;
            log.debug("TTS generated: {} ({} bytes)", outputPath, audio.length);
            return ToolResult.ok(result);
        } catch (Exception e) {
            log.error("TTS failed: {}", e.getMessage(), e);
            return ToolResult.fail("TTS failed: " + e.getMessage());
        }
    }

    private ToolResult validateOutputPath(String outputPath) {
        String normalized = outputPath.replace('\\', '/');
        if (normalized.matches(".*(^|/)\\.\\.(/|$).*$")) {
            return ToolResult.fail("output_path contains '..' traversal component. Use an absolute path or a relative path without '..'.");
        }
        Path resolved = Path.of(outputPath).toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path etc = Path.of("/etc").toAbsolutePath().normalize();
        Path proc = Path.of("/proc").toAbsolutePath().normalize();
        if (resolved.startsWith(home.resolve(".ssh")) || resolved.startsWith(etc) || resolved.startsWith(proc)) {
            return ToolResult.fail("output_path targets a protected credential or system path. Choose a normal audio output location.");
        }
        return null;
    }

    private TtsProvider resolveProvider(String requested) {
        String name = requested != null && !requested.isBlank()
            ? requested.trim() : properties.getTts().getProvider();
        return providers.stream()
            .filter(provider -> provider.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    record TtsArgs(
        String text,
        @JsonProperty("output_path") String outputPath,
        @JsonProperty("voice") String voice,
        @JsonProperty("speed") Double speed,
        @JsonProperty("instructions") String instructions,
        @JsonProperty("provider") String provider
    ) {}
}