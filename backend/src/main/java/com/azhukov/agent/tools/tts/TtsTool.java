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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private final ObjectProvider<TtsProvider> providerProvider;
    private final AgentProperties properties;


    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TtsArgs args = ToolHandler.parseJson(arguments, TtsArgs.class);

        if (args.text() == null || args.text().isBlank()) {
            return ToolResult.fail("text is required");
        }

        TtsProvider provider = providerProvider.getIfAvailable();
        if (provider == null) {
            return ToolResult.fail("TTS is not enabled. Set agent.tts.enabled=true to use this tool.");
        }

        String voice = args.voice() != null ? args.voice() : properties.getTts().getVoice();

        try {
            byte[] audio = provider.synthesize(args.text(), voice);
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

    record TtsArgs(
        String text,
        @JsonProperty("output_path") String outputPath,
        @JsonProperty("voice") String voice,
        @JsonProperty("speed") Double speed,
        @JsonProperty("instructions") String instructions,
        @JsonProperty("provider") String provider
    ) {}
}