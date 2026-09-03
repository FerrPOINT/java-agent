package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_get_images",
    description = "Return a list of image URLs on the current page.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserGetImagesTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        try {
            String script = """
                Array.from(document.querySelectorAll('img'))
                  .map(img => ({
                    src: img.src,
                    alt: img.alt || '',
                    width: img.naturalWidth,
                    height: img.naturalHeight
                  }))
                  .filter(i => i.src && !i.src.startsWith('data:'))
                  .slice(0, 20)
                """;
            return ToolResult.ok(browserService.evaluate(script));
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser get_images failed: " + e.getMessage());
        }
    }
}
