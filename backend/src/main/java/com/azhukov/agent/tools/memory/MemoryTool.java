package com.azhukov.agent.tools.memory;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "memory",
    description = "Store or recall a memory fact for the user.",
    toolset = "memory"
)
@Component
public class MemoryTool implements ToolHandler {

    private final MemoryProvider memoryProvider;

    public MemoryTool(MemoryProvider memoryProvider) {
        this.memoryProvider = memoryProvider;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        MemoryArgs args = ToolHandler.parseJson(arguments, MemoryArgs.class);
        if ("store".equalsIgnoreCase(args.action())) {
            memoryProvider.store(session.userId(), args.category(), args.content());
            return ToolResult.ok("Stored memory.");
        }
        var facts = memoryProvider.recall(session.userId(), args.content(), args.limit());
        return ToolResult.ok(String.join("\n", facts));
    }

    public record MemoryArgs(
        @ToolParam(description = "store or recall") String action,
        @ToolParam(description = "memory category") String category,
        @ToolParam(description = "content to store or query to recall") String content,
        @ToolParam(description = "max results for recall") int limit
    ) {}
}
