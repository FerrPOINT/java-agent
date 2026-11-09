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
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.FileSafety;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.tools.file.FileToolSafety;
import com.azhukov.agent.service.ImageShrinkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.file.NoSuchFileException;
import java.util.Base64;

@AgentTool(
    name = "vision_analyze",
    description = "Load an image into the conversation so you can see it. Accepts a URL, local file path, or data URL. When your active model has native vision, the image is attached to your context directly and you read the pixels yourself on the next turn — call this any time the user references an image (filepath in their message, URL in tool output, screenshot from the browser, etc.). For non-vision models, falls back to an auxiliary vision model that returns a text description.",
    toolset = "vision"
)
@Component
@RequiredArgsConstructor
public class VisionAnalyzeTool implements ToolHandler {

    private final ModelClient modelClient;
    private final ImageShrinkerService imageShrinker;
    private final UrlSafety urlSafety;
    private final AgentProperties properties;
    private final FileSafety fileSafety;

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
            String base64 = loadImageBase64(args.image(), session);
            // P2-15: shrink image if it exceeds provider payload limits
            String shrunkBase64 = imageShrinker.shrinkIfNeeded(base64);
            String result = modelClient.analyzeImage(shrunkBase64, args.prompt() != null ? args.prompt() : "Describe this image");
            return ToolResult.ok(result);
        } catch (Exception e) {
            return ToolResult.fail("Vision analyze failed: " + e.getMessage());
        }
    }

    private String loadImageBase64(String source, Session session) throws Exception {
        byte[] bytes;
        if (source.startsWith("http://") || source.startsWith("https://")) {
            URI current = URI.create(source);
            for (int redirects = 0; ; redirects++) {
                String scheme = current.getScheme();
                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    throw new IllegalArgumentException("Unsupported image URL scheme");
                }
                String blockReason = urlSafety.checkUrl(current.toString());
                if (blockReason != null) throw new IllegalArgumentException("blocked: " + blockReason);
                var connection = (java.net.HttpURLConnection) current.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(FETCH_TIMEOUT_MS);
                connection.setReadTimeout(FETCH_TIMEOUT_MS);
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.isBlank() || redirects == 5) {
                            throw new IllegalArgumentException("Invalid or excessive image redirects");
                        }
                        current = current.resolve(location);
                        continue;
                    }
                    try (InputStream in = connection.getInputStream()) {
                        bytes = in.readNBytes((int) MAX_IMAGE_BYTES + 1);
                    }
                    break;
                } finally {
                    connection.disconnect();
                }
            }
        } else {
            var path = FileToolSafety.resolvePath(source, session);
            try (var channel = FileToolSafety.openReadable(properties, fileSafety, path, source);
                 var input = Channels.newInputStream(channel)) {
                bytes = input.readNBytes((int) MAX_IMAGE_BYTES + 1);
            } catch (NoSuchFileException missing) {
                throw new IllegalArgumentException("File not found: " + source, missing);
            }
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image exceeds 50MB limit: " + bytes.length + " bytes");
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    public record VisionArgs(
        @ToolParam(description = "Image URL (http/https), local file path, or data URL to load.") @JsonProperty("image_url") @JsonAlias({"image", "imageUrl"}) String image,
        @ToolParam(description = "Your specific question or request about the image.") @JsonProperty("question") @JsonAlias("prompt") String prompt,
        @ToolParam(description = "Optional [x1, y1, x2, y2] crop region in pixel coordinates.", required = false) int[] region
    ) {}
}
