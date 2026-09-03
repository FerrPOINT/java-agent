package com.azhukov.agent.tools.vision;

import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.service.ImageShrinkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@AgentTool(
    name = "vision_analyze",
    description = "Load an image into the conversation so you can see it. Accepts a URL, local file path, or data URL. When your active model has native vision, the image is attached to your context directly and you read the pixels yourself on the next turn — call this any time the user references an image (filepath in their message, URL in tool output, screenshot from the browser, etc.). For non-vision models, falls back to an auxiliary vision model that returns a text description.",
    toolset = "vision"
)
@Component
@RequiredArgsConstructor
public class VisionAnalyzeTool implements ToolHandler {

    private static final int MAX_SAFE_REDIRECTS = 10;
    private static final int MAX_IMAGE_DOWNLOAD_BYTES = 50 * 1024 * 1024;
    private static final int DOWNLOAD_TIMEOUT_MILLIS = 30_000;
    private static final int DOWNLOAD_BUFFER_BYTES = 16 * 1024;
    private static final ObjectMapper JSON = SharedObjectMapper.get();

    private final ModelClient modelClient;
    private final ImageShrinkerService imageShrinker;
    private final UrlSafety urlSafety;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        VisionArgs args;
        try {
            args = ToolHandler.parseJson(arguments, VisionArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonFail(e.getMessage());
        }
        if (args.image() == null || args.image().isBlank()) {
            return jsonFail("image_url is required");
        }
        try {
            String base64 = loadImageBase64(args.image(), args.region());
            // P2-15: shrink image if it exceeds provider payload limits
            String shrunkBase64 = imageShrinker.shrinkIfNeeded(base64);
            String result = modelClient.analyzeImage(shrunkBase64, args.prompt() != null ? args.prompt() : "Describe this image");
            return ToolResult.ok(result);
        } catch (Exception e) {
            return jsonFail("Vision analyze failed: " + e.getMessage());
        }
    }

    private static ToolResult jsonFail(String error) {
        Map<String, Object> response = new LinkedHashMap<>();
        String message = error == null ? "Vision analyze failed" : error;
        response.put("success", false);
        response.put("error", message);
        try {
            return new ToolResult(false, JSON.writeValueAsString(response), message);
        } catch (Exception e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"Vision analyze failed\"}", message);
        }
    }

    private String loadImageBase64(String source, int[] region) throws Exception {
        String trimmed = source.trim();
        String lowerSource = trimmed.toLowerCase(Locale.ROOT);
        if (lowerSource.startsWith("data:image/")) {
            int comma = trimmed.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("Invalid image data URL");
            }
            String header = trimmed.substring(0, comma).toLowerCase(Locale.ROOT);
            if (!header.contains(";base64")) {
                throw new IllegalArgumentException("Only base64 image data URLs are supported");
            }
            byte[] bytes = Base64.getMimeDecoder().decode(trimmed.substring(comma + 1));
            ensureRecognizedImage(bytes);
            if (region != null) {
                bytes = cropImageRegion(bytes, region);
            }
            return Base64.getEncoder().encodeToString(bytes);
        }

        byte[] bytes;
        if (lowerSource.startsWith("http://") || lowerSource.startsWith("https://")) {
            String blockReason = urlSafety.checkUrl(trimmed);
            if (blockReason != null) {
                throw new SecurityException(blockReason);
            }
            bytes = downloadHttpImage(trimmed);
        } else if (lowerSource.startsWith("file:")) {
            Path path = Paths.get(URI.create(trimmed));
            bytes = readLocalImage(path);
        } else {
            bytes = readLocalImage(Paths.get(trimmed));
        }
        ensureRecognizedImage(bytes);
        if (region != null) {
            bytes = cropImageRegion(bytes, region);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private byte[] readLocalImage(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        long size = Files.size(path);
        if (size > MAX_IMAGE_DOWNLOAD_BYTES) {
            throw new IOException("Image too large (" + size + " bytes, max "
                + MAX_IMAGE_DOWNLOAD_BYTES + ")");
        }
        return Files.readAllBytes(path);
    }

    private byte[] cropImageRegion(byte[] bytes, int[] region) throws IOException {
        if (region.length != 4) {
            throw new IllegalArgumentException(
                "Invalid region: expected [x1, y1, x2, y2] as four numbers "
                    + "(pixel coordinates in the original image).");
        }

        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IllegalArgumentException("Failed to crop region: unsupported image format");
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int x1 = region[0];
            int y1 = region[1];
            int x2 = region[2];
            int y2 = region[3];
            int cx1 = Math.max(0, Math.min(x1, width));
            int cy1 = Math.max(0, Math.min(y1, height));
            int cx2 = Math.max(0, Math.min(x2, width));
            int cy2 = Math.max(0, Math.min(y2, height));
            if (cx2 <= cx1 || cy2 <= cy1) {
                throw new IllegalArgumentException("Invalid region [" + x1 + ", " + y1 + ", " + x2 + ", " + y2
                    + "]: crops to zero area after clamping to the image bounds. The image is "
                    + width + "x" + height + " px - pick x1<x2 and y1<y2 inside [0, 0, "
                    + width + ", " + height + "].");
            }

            BufferedImage subImage = image.getSubimage(cx1, cy1, cx2 - cx1, cy2 - cy1);
            BufferedImage copy = new BufferedImage(subImage.getWidth(), subImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = copy.createGraphics();
            try {
                graphics.drawImage(subImage, 0, 0, null);
            } finally {
                graphics.dispose();
            }

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (!ImageIO.write(copy, "png", out)) {
                    throw new IOException("Failed to encode cropped region as PNG");
                }
                return out.toByteArray();
            }
        }
    }

    private void ensureRecognizedImage(byte[] bytes) {
        if (detectImageMimeType(bytes) != null) {
            return;
        }
        if (looksLikeSvg(bytes)) {
            throw new IllegalArgumentException(
                "SVG images are not supported by this Java vision path; convert to PNG or JPEG and retry.");
        }
        throw new IllegalArgumentException("source is not a recognized image");
    }

    private String detectImageMimeType(byte[] bytes) {
        if (bytes.length >= 8
            && (bytes[0] & 0xFF) == 0x89
            && bytes[1] == 'P'
            && bytes[2] == 'N'
            && bytes[3] == 'G'
            && bytes[4] == '\r'
            && bytes[5] == '\n'
            && (bytes[6] & 0xFF) == 0x1A
            && bytes[7] == '\n') {
            return "image/png";
        }
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xD8
            && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 6
            && ((bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && bytes[4] == '7' && bytes[5] == 'a')
            || (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && bytes[4] == '9' && bytes[5] == 'a'))) {
            return "image/gif";
        }
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
            return "image/bmp";
        }
        if (bytes.length >= 12
            && bytes[0] == 'R'
            && bytes[1] == 'I'
            && bytes[2] == 'F'
            && bytes[3] == 'F'
            && bytes[8] == 'W'
            && bytes[9] == 'E'
            && bytes[10] == 'B'
            && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private boolean looksLikeSvg(byte[] bytes) {
        int limit = Math.min(bytes.length, 4096);
        String prefix = new String(bytes, 0, limit, java.nio.charset.StandardCharsets.UTF_8)
            .toLowerCase(Locale.ROOT);
        return prefix.contains("<svg");
    }

    private byte[] downloadHttpImage(String url) throws Exception {
        URI current = URI.create(url);
        for (int redirectCount = 0; redirectCount <= MAX_SAFE_REDIRECTS; redirectCount++) {
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(DOWNLOAD_TIMEOUT_MILLIS);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; JavaAgent/1.0)");
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.8");
            try {
                int status = connection.getResponseCode();
                if (isRedirectStatus(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Redirect response missing Location header");
                    }
                    URI next = current.resolve(location);
                    String nextUrl = next.toString();
                    String blockReason = urlSafety.checkUrl(nextUrl);
                    if (blockReason != null) {
                        throw new SecurityException("Redirect blocked: " + blockReason);
                    }
                    current = next;
                    continue;
                }
                if (status >= 400) {
                    throw new IOException("HTTP " + status + " while downloading image");
                }

                long declaredLength = connection.getContentLengthLong();
                if (declaredLength > MAX_IMAGE_DOWNLOAD_BYTES) {
                    throw new IOException("Image too large (" + declaredLength + " bytes, max "
                            + MAX_IMAGE_DOWNLOAD_BYTES + ")");
                }

                try (InputStream in = connection.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[DOWNLOAD_BUFFER_BYTES];
                    int total = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_IMAGE_DOWNLOAD_BYTES) {
                            throw new IOException("Image too large (" + total + " bytes, max "
                                    + MAX_IMAGE_DOWNLOAD_BYTES + ")");
                        }
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private boolean isRedirectStatus(int status) {
        return status >= 300 && status < 400;
    }

    public record VisionArgs(
        @ToolParam(description = "Image URL (http/https), local file path, or data URL to load.") @JsonProperty("image_url") @JsonAlias("image") String image,
        @ToolParam(description = "Your specific question or request about the image.") @JsonProperty("question") @JsonAlias("prompt") String prompt,
        @ToolParam(description = "Optional [x1, y1, x2, y2] crop region in pixel coordinates.", required = false) int[] region
    ) {}
}
