package com.azhukov.agent.tools.imagegen;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.imagegen.ImageGenProvider;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
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

    private final org.springframework.beans.factory.ObjectProvider<ImageGenProvider> providerProvider;
    private final com.azhukov.agent.config.AgentProperties properties;


    /**
     * Hermes parity (tools/registry.py check_fn): a tool whose provider is not
     * configured is hidden from the model's tool schema instead of being
     * registered and failing at call time. True when the configured provider
     * bean exists (agent.image-gen.enabled=true loads pollinations/openai).
     */
    public boolean isToolAvailable() {
        return resolveProvider() != null;
    }

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

        ImageGenProvider provider = resolveProvider();
        if (provider == null) {
            String configured = properties.getImageGen().getProvider();
            String hint = configured != null && !configured.isBlank() && !"pollinations".equalsIgnoreCase(configured)
                ? " The configured provider '" + configured + "' has no implementation."
                : "";
            return jsonFailureResponse(
                "Image generation is disabled by configuration. Set agent.image-gen.enabled=true"
                    + " (env AGENT_IMAGE_GEN_ENABLED) to use this tool." + hint,
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
            String fileName = "img_" + UUID.randomUUID() + imageExtension(imageBytes);
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

    private String imageExtension(byte[] image) {
        // Providers may return JPEG even when their endpoint has no filename extension.
        // Persist a truthful suffix so MEDIA delivery and downstream image decoders agree.
        if (image.length >= 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 && (image[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (image.length >= 8 && (image[0] & 0xFF) == 0x89 && image[1] == 0x50 && image[2] == 0x4E && image[3] == 0x47
            && image[4] == 0x0D && image[5] == 0x0A && image[6] == 0x1A && image[7] == 0x0A) {
            return ".png";
        }
        if (image.length >= 6 && image[0] == 'G' && image[1] == 'I' && image[2] == 'F'
            && image[3] == '8' && (image[4] == '7' || image[4] == '9') && image[5] == 'a') {
            return ".gif";
        }
        if (image.length >= 12 && image[0] == 'R' && image[1] == 'I' && image[2] == 'F' && image[3] == 'F'
            && image[8] == 'W' && image[9] == 'E' && image[10] == 'B' && image[11] == 'P') {
            return ".webp";
        }
        return ".png"; // Preserve the historical suffix for an unrecognized provider payload.
    }

    private ImageGenProvider resolveProvider() {
        List<ImageGenProvider> available = providerProvider.stream().toList();
        if (available.isEmpty()) {
            return null;
        }
        String configured = properties.getImageGen().getProvider();
        if (configured != null && !configured.isBlank()) {
            for (ImageGenProvider provider : available) {
                if (provider.name().equalsIgnoreCase(configured.trim())) {
                    return provider;
                }
            }
            return null;
        }
        return available.size() == 1 ? available.getFirst() : null;
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
        @ToolParam(description = "Aspect ratio: landscape, square, or portrait. Legacy aliases 16:9, 1:1, and 9:16 are accepted. Default landscape.", required = false) @JsonProperty("aspect_ratio") @JsonAlias("aspectRatio") String aspectRatio,
        @ToolParam(description = "Optional source image URL for image-to-image editing (model must support edit endpoint).", required = false) @JsonProperty("image_url") @JsonAlias("imageUrl") String imageUrl,
        @ToolParam(description = "Optional list of reference image URLs for multi-image editing.", required = false) @JsonProperty("reference_image_urls") @JsonAlias("referenceImageUrls") List<String> referenceImageUrls,
        @ToolParam(description = "Optional high-resolution post-generation pass. Unsupported by the current Java provider.", required = false) @JsonProperty("upscale") Boolean upscale
    ) {}
}
