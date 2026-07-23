package com.azhukov.agent.tools.vision;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "vision_analyze",
    description = "Analyze an image (file path or URL) using a vision-capable model.",
    toolset = "browser"
)
@Component
public class VisionAnalyzeTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        VisionArgs args = ToolHandler.parseJson(arguments, VisionArgs.class);
        return ToolResult.ok("Vision analyze not yet implemented. Image: " + args.image());
    }

    public record VisionArgs(
        @ToolParam(description = "local file path or URL of the image") String image,
        @ToolParam(description = "question or instructions about the image") String prompt
    ) {}
}
