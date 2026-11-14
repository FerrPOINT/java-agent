package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.FileSafety;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.AttachmentArtifactService;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;

import java.nio.channels.Channels;
import java.util.Map;

/** Publishes an explicitly requested readable file as a scoped attachment. */
@Component
@RequiredArgsConstructor
@AgentTool(
    name = "publish_file",
    description = "Publish an existing file the user asked to receive, such as a document or archive. "
        + "Uses the current file-read permissions and session owner; maximum 20 MiB. "
        + "Return the exact MEDIA:artifact reference in the final response for Telegram delivery. "
        + "Publication is not a platform delivery receipt.",
    toolset = "file"
)
public class PublishFileTool implements ToolHandler {

    private final AgentProperties properties;
    private final FileSafety fileSafety;
    private final AttachmentArtifactService artifacts;

    /** Publishes the selected readable bytes, returning an artifact reference or an explicit failure. */
    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        try {
            UserContext.requireCurrentAccess();
            PublishFileArgs args = ToolHandler.parseJson(arguments, PublishFileArgs.class);
            if (args == null || args.path() == null || args.path().isBlank()) {
                return ToolResult.fail("path is required");
            }
            if (session == null || session.id() == null) {
                return ToolResult.fail("An active session is required to publish a file");
            }
            var path = FileToolSafety.resolvePath(args.path(), session);
            byte[] bytes;
            try (var channel = FileToolSafety.openReadable(properties, fileSafety, path, args.path())) {
                if (channel.size() > AttachmentArtifactService.MAX_ATTACHMENT_BYTES) {
                    return ToolResult.fail("File exceeds the 20 MiB attachment limit");
                }
                bytes = Channels.newInputStream(channel).readNBytes(
                    Math.toIntExact(AttachmentArtifactService.MAX_ATTACHMENT_BYTES + 1));
                if (bytes.length > AttachmentArtifactService.MAX_ATTACHMENT_BYTES) {
                    return ToolResult.fail("File exceeds the 20 MiB attachment limit");
                }
            }
            UserContext.requireCurrentAccess();
            String name = AttachmentArtifactService.sanitizeFileName(path.getFileName().toString());
            String mime = MediaTypeFactory.getMediaType(name)
                .orElse(MediaType.APPLICATION_OCTET_STREAM).toString();
            String reference = artifacts.publishGenerated(session, "document", mime, name, bytes);
            return ToolResult.ok(SharedObjectMapper.get().writeValueAsString(
                Map.of("media_tag", reference, "file_name", name, "size_bytes", bytes.length)));
        } catch (Exception e) {
            return ToolResult.fail("File publication failed: " + e.getMessage());
        }
    }

    /** Path of the deliverable explicitly selected for publication. */
    public record PublishFileArgs(
        @ToolParam(description = "Path of the existing file the user asked to receive.") String path
    ) {}
}
