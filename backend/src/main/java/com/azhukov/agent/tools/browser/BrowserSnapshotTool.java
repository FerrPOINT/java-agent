package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_snapshot",
    description = "Return accessibility-style text snapshot of the current page (title + links + inputs).",
    toolset = "browser"
)
@Component
public class BrowserSnapshotTool implements ToolHandler {

    private final BrowserService browserService;

    public BrowserSnapshotTool(BrowserService browserService) {
        this.browserService = browserService;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        try {
            String script = """
                const title = document.title;
                const links = Array.from(document.querySelectorAll('a')).slice(0, 30).map(a => a.href + ' | ' + a.innerText.trim()).join('\\n');
                const inputs = Array.from(document.querySelectorAll('input, textarea, select, button')).slice(0, 20)
                  .map(el => (el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (el.name ? '[name=' + el.name + ']' : '') + ' | ' + (el.placeholder || el.value || '')))
                  .join('\\n');
                return 'Title: ' + title + '\\n\\nLinks:\\n' + links + '\\n\\nInputs:\\n' + inputs;
                """;
            return ToolResult.ok(browserService.evaluate(script));
        } catch (Exception e) {
            return ToolResult.fail("Browser snapshot failed: " + e.getMessage());
        }
    }
}
