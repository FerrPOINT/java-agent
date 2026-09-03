package com.azhukov.agent.tools.vision;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.service.ImageShrinkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

@AgentTool(
    name = "vision_analyze",
    description = "Load an image into the conversation so you can see it. Accepts a URL, local file path, or data URL. When your active model has native vision, the image is attached to your context directly and you read the pixels yourself on the next turn — call this any time the user references an image (filepath in their message, URL in tool output, screenshot from the browser, etc.). For non-vision models, falls back to an auxiliary vision model that returns a text description.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class VisionAnalyzeTool implements ToolHandler {

    private final ModelClient modelClient;
    private final ImageShrinkerService imageShrinker;
    private final UrlSafety urlSafety;

    /** Hermes parity (image_source._download_to_bytes): 50MB stream cap. */
    private static final long MAX_IMAGE_BYTES = 50L * 1024 * 1024;
    /** Connect/read timeout for remote image fetches. */
    private static final int FETCH_TIMEOUT_MS = 20_000;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        VisionArgs args = ToolHandler.parseJson(arguments, VisionArgs.class);
        if (args.image() == null || args.image().isBlank()) {
            return ToolResult.fail("Image path or URL is required");
        }
        try {
            String base64 = loadImageBase64(args.image());
            // P2-15: shrink image if it exceeds provider payload limits
            String shrunkBase64 = imageShrinker.shrinkIfNeeded(base64);
            String result = modelClient.analyzeImage(shrunkBase64, args.prompt() != null ? args.prompt() : "Describe this image");
            return ToolResult.ok(result);
        } catch (Exception e) {
            return ToolResult.fail("Vision analyze failed: " + e.getMessage());
        }
    }

    private String loadImageBase64(String source) throws Exception {
        byte[] bytes;
        if (source.startsWith("http://") || source.startsWith("https://")) {
            // Hermes parity (image_source._http_block_reason): refuse unsafe or
            // private URLs BEFORE any network I/O — raw openStream() allowed
            // SSRF probes against internal networks.
            String blockReason = urlSafety.checkUrl(source);
            if (blockReason != null) {
                throw new IllegalArgumentException("blocked: " + blockReason);
            }
            var connection = URI.create(source).toURL().openConnection();
            connection.setConnectTimeout(FETCH_TIMEOUT_MS);
            connection.setReadTimeout(FETCH_TIMEOUT_MS);
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                bytes = out.toByteArray();
            }
            // Hermes parity: 50MB stream cap (image_source.SourceTooLarge)
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Image exceeds 50MB limit: " + bytes.length + " bytes");
            }
        } else {
            Path path = Paths.get(source);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("File not found: " + source);
            }
            bytes = Files.readAllBytes(path);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    public record VisionArgs(
        @ToolParam(description = "Image URL (http/https), local file path, or data URL to load.") @JsonProperty("image_url") @JsonAlias("image") String image,
        @ToolParam(description = "Your specific question or request about the image.") @JsonProperty("question") @JsonAlias("prompt") String prompt,
        @ToolParam(description = "Optional [x1, y1, x2, y2] crop region in pixel coordinates.", required = false) int[] region
    ) {}
}
