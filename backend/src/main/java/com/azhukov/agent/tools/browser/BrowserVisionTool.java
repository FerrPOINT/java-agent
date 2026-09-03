package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@AgentTool(
    name = "browser_vision",
    description = "Capture a screenshot of the current page and return a PNG data URL plus screenshot_path/MEDIA path metadata.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserVisionTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        try {
            ToolHandler.parseJson(arguments, VisionArgs.class);
        } catch (IllegalArgumentException e) {
            return BrowserToolResponses.failureResult(e.getMessage());
        }
        try {
            BrowserService.BrowserScreenshot screenshot = browserService.captureScreenshot();
            if (!screenshot.success()) {
                return BrowserToolResponses.failureResult(screenshot.error());
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("data_url", screenshot.dataUrl());
            response.put("screenshot_path", screenshot.screenshotPath());
            response.put("media_tag", screenshot.mediaTag());
            response.put("mime_type", screenshot.mimeType());
            return ToolResult.ok(BrowserToolResponses.success(response));
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser screenshot failed: " + e.getMessage());
        }
    }

    public record VisionArgs(
        @ToolParam(description = "What you want to know about the page visually. Be specific about what you're looking for.") String question,
        @ToolParam(description = "If true, overlay numbered labels on interactive elements.", required = false) boolean annotate
    ) {}
}
