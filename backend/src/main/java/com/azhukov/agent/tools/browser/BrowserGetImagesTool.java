package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_get_images",
    description = "Return a list of image URLs on the current page.",
    toolset = "browser"
)
@Component
public class BrowserGetImagesTool implements ToolHandler {

    private final BrowserService browserService;

    public BrowserGetImagesTool(BrowserService browserService) {
        this.browserService = browserService;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        try {
            String script = """
                Array.from(document.querySelectorAll('img'))
                  .map(img => ({ src: img.src, alt: img.alt }))
                  .filter(i => i.src)
                  .slice(0, 20)
                """;
            return ToolResult.ok(browserService.evaluate(script));
        } catch (Exception e) {
            return ToolResult.fail("Browser get_images failed: " + e.getMessage());
        }
    }
}
