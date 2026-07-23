package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class DefaultAgentRuntime implements AgentRuntime {

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final ContextEngine contextEngine;
    private final MemoryProvider memoryProvider;
    private final SkillManager skillManager;
    private final AgentProperties properties;

    public DefaultAgentRuntime(ModelClient modelClient, ToolRegistry toolRegistry,
                               PromptBuilder promptBuilder, ContextEngine contextEngine,
                               MemoryProvider memoryProvider, SkillManager skillManager,
                               AgentProperties properties) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.promptBuilder = promptBuilder;
        this.contextEngine = contextEngine;
        this.memoryProvider = memoryProvider;
        this.skillManager = skillManager;
        this.properties = properties;
    }

    @Override
    public TurnResult runTurn(Session session, String userInput) {
        List<Message> turnMessages = new ArrayList<>();
        turnMessages.add(promptBuilder.buildSystemMessage(session));
        turnMessages.add(Message.user(userInput));

        List<ToolDefinition> tools = toolRegistry.getDefinitions();
        int maxTurns = properties.getCore().getMaxTurns();

        for (int i = 0; i < maxTurns; i++) {
            List<Message> context = contextEngine.prepareContext(session, turnMessages);
            ChatResponse response = modelClient.complete(context, tools);

            if (!response.hasToolCalls()) {
                turnMessages.add(Message.assistant(response.content()));
                return new TurnResult(turnMessages, true, null);
            }

            Message assistantMessage = Message.assistant("");
            // Tool calls are stored conceptually; for simplicity append the calls to context as assistant message
            turnMessages.add(assistantMessage);

            for (ToolCall call : response.toolCalls()) {
                ToolResult result = toolRegistry.execute(call.name(), call.id(), call.arguments(), assistantMessage, session);
                turnMessages.add(Message.toolResult(call.id(), formatResult(result)));
            }

            // After first tool execution return the result as a completed turn for the happy path.
            // Real implementation would loop back to the model.
            Message finalMessage = Message.assistant(formatToolResults(response.toolCalls(), turnMessages));
            turnMessages.add(finalMessage);
            return new TurnResult(turnMessages, true, null);
        }

        return TurnResult.error("Reached max turns without completion");
    }

    private String formatResult(ToolResult result) {
        if (result.success()) {
            return result.content();
        }
        return "Error: " + result.error();
    }

    private String formatToolResults(List<ToolCall> calls, List<Message> messages) {
        // Simple formatter: collect last N tool results
        StringBuilder sb = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.role() == Role.TOOL) {
                sb.insert(0, m.content() + "\n");
            }
        }
        return "Tool results:\n" + sb.toString().trim();
    }
}
