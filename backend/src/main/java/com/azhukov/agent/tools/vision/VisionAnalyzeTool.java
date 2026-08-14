package com.azhukov.agent.tools.vision;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.ImageShrinker;
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
    description = "Analyze an image (file path or URL) using a vision-capable model.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class VisionAnalyzeTool implements ToolHandler {

    private final ModelClient modelClient;
    private final ImageShrinker imageShrinker;

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
            try (InputStream in = URI.create(source).toURL().openStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                bytes = out.toByteArray();
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
        @ToolParam(description = "local file path or URL of the image") String image,
        @ToolParam(description = "question or instructions about the image") String prompt
    ) {}
}
