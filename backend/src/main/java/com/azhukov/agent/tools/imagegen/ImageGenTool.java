package com.azhukov.agent.tools.imagegen;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.imagegen.ImageGenProvider;
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
 * Agent tool for generating images from text prompts.
 * Saves generated images to /tmp/ and returns the file path.
 */
@AgentTool(
    name = "image_generate",
    description = "Generate an image from a text prompt. Returns the file path to the generated image.",
    toolset = "core"
)
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageGenTool implements ToolHandler {

    private final ObjectProvider<ImageGenProvider> providerProvider;


    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ImageGenArgs args = ToolHandler.parseJson(arguments, ImageGenArgs.class);

        if (args.prompt() == null || args.prompt().isBlank()) {
            return ToolResult.fail("prompt is required");
        }

        String aspectRatio = args.aspectRatio() != null ? args.aspectRatio() : "1:1";

        ImageGenProvider provider = providerProvider.getIfAvailable();
        if (provider == null) {
            return ToolResult.fail("Image generation is not enabled. Set agent.image-gen.enabled=true to use this tool.");
        }

        try {
            byte[] imageBytes = provider.generate(args.prompt(), aspectRatio);
            String fileName = "img_" + UUID.randomUUID() + ".png";
            Path outputPath = Path.of("/tmp", fileName);
            Files.write(outputPath, imageBytes);
            String result = "MEDIA:" + outputPath.toString() + "\nImage generated and saved to " + outputPath;
            log.debug("Image generated: {} ({} bytes)", outputPath, imageBytes.length);
            return ToolResult.ok(result);
        } catch (Exception e) {
            log.error("Image generation failed: {}", e.getMessage(), e);
            return ToolResult.fail("Image generation failed: " + e.getMessage());
        }
    }

    record ImageGenArgs(
        String prompt,
        @JsonProperty("aspect_ratio") String aspectRatio
    ) {}
}