package com.azhukov.agent.tools.imagegen;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.imagegen.ImageGenProvider;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Agent tool for generating images from text prompts.
 * Saves generated images to the system temp directory and returns the file path.
 */
@AgentTool(
    name = "image_generate",
    description = "Generate an image from a text prompt. Returns the file path to the generated image.",
    toolset = "image_gen"
)
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageGenTool implements ToolHandler {

    private static final Set<String> VALID_ASPECT_RATIOS = Set.of("landscape", "square", "portrait");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectProvider<ImageGenProvider> providerProvider;


    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ImageGenArgs args;
        try {
            args = ToolHandler.parseJson(arguments, ImageGenArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonFailureResponse(e.getMessage(), "ValueError");
        }

        if (args.prompt() == null || args.prompt().isBlank()) {
            return jsonFailureResponse("prompt is required for image generation", "ValueError");
        }

        String aspectRatio = normalizeAspectRatio(args.aspectRatio());

        ImageGenProvider provider = providerProvider.getIfAvailable();
        if (provider == null) {
            return jsonFailureResponse(
                "Image generation is not enabled. Set agent.image-gen.enabled=true to use this tool.",
                "ValueError"
            );
        }
        if (Boolean.TRUE.equals(args.upscale())) {
            return jsonFailureResponse(
                "Image upscale is not supported by the configured Java image generation provider. "
                    + "Omit upscale or set it to false.",
                "ValueError"
            );
        }
        if (hasSourceImages(args)) {
            return jsonFailureResponse(
                "Image editing is not supported by the configured Java image generation provider. "
                    + "Omit image_url/reference_image_urls for text-to-image generation.",
                "ValueError"
            );
        }

        try {
            byte[] imageBytes = provider.generate(args.prompt(), aspectRatio);
            if (imageBytes == null || imageBytes.length == 0) {
                throw new IllegalStateException("Image generation returned empty image data");
            }
            String fileName = "img_" + UUID.randomUUID() + ".png";
            Path outputPath = defaultOutputDir().resolve(fileName)
                .toAbsolutePath()
                .normalize();
            Files.createDirectories(outputPath.getParent());
            Files.write(outputPath, imageBytes);
            log.debug("Image generated: {} ({} bytes)", outputPath, imageBytes.length);
            return ToolResult.ok(jsonSuccessResponse(outputPath));
        } catch (Exception e) {
            log.error("Image generation failed: {}", e.getMessage(), e);
            return jsonFailureResponse(errorMessage(e), e.getClass().getSimpleName());
        }
    }

    private ToolResult jsonFailureResponse(String error, String errorType) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", false);
        response.putNull("image");
        response.put("error", error);
        response.put("error_type", errorType);
        return new ToolResult(false, response.toString(), error);
    }

    private String jsonSuccessResponse(Path imagePath) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", true);
        response.put("image", imagePath.toString());
        response.put("modality", "text");
        response.put("upscaled", false);
        return response.toString();
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
            ? e.getClass().getSimpleName()
            : e.getMessage();
    }

    private boolean hasSourceImages(ImageGenArgs args) {
        if (args.imageUrl() != null && !args.imageUrl().isBlank()) {
            return true;
        }
        return args.referenceImageUrls() != null
            && args.referenceImageUrls().stream().anyMatch(ref -> ref != null && !ref.isBlank());
    }

    private String normalizeAspectRatio(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) {
            return "landscape";
        }
        String normalized = aspectRatio.trim().toLowerCase(Locale.ROOT);
        String mapped = switch (normalized) {
            case "16:9" -> "landscape";
            case "1:1" -> "square";
            case "9:16" -> "portrait";
            default -> normalized;
        };
        return VALID_ASPECT_RATIOS.contains(mapped) ? mapped : "landscape";
    }

    private Path defaultOutputDir() {
        String hermesHome = System.getenv("HERMES_HOME");
        if (hermesHome != null && !hermesHome.isBlank()) {
            return Path.of(hermesHome).toAbsolutePath().normalize().resolve("cache/images");
        }
        return Path.of(System.getProperty("user.home"), ".hermes", "cache", "images")
            .toAbsolutePath()
            .normalize();
    }

    record ImageGenArgs(
        @ToolParam(description = "Text prompt describing the image to generate.") String prompt,
        @ToolParam(description = "Aspect ratio: landscape, square, or portrait. Legacy aliases 16:9, 1:1, and 9:16 are accepted. Default landscape.", required = false) @JsonProperty("aspect_ratio") String aspectRatio,
        @ToolParam(description = "Optional source image URL for image-to-image editing (model must support edit endpoint).", required = false) @JsonProperty("image_url") String imageUrl,
        @ToolParam(description = "Optional list of reference image URLs for multi-image editing.", required = false) @JsonProperty("reference_image_urls") List<String> referenceImageUrls,
        @ToolParam(description = "Optional high-resolution post-generation pass. Unsupported by the current Java provider.", required = false) @JsonProperty("upscale") Boolean upscale
    ) {}
}
