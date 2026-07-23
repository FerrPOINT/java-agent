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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class DefaultAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRuntime.class);

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
    public ChatResponse run(List<Message> messages, List<ToolDefinition> tools) {
        List<Message> context = contextEngine.prepareContext(
            Session.create("openai-user", "openai-compatible", ""), messages);
        return modelClient.complete(context, tools);
    }

    @Override
    public TurnResult runTurn(Session session, String userInput) {
        List<Message> turnMessages = new ArrayList<>();
        turnMessages.add(promptBuilder.buildSystemMessage(session));
        turnMessages.add(Message.user(userInput));

        List<ToolDefinition> tools = toolRegistry.getDefinitions(new HashSet<>(properties.getSkills().getDefaultToolsets()));
        int maxTurns = properties.getCore().getMaxTurns();
        int turnIndex = 1;

        for (int i = 0; i < maxTurns; i++) {
            long turnStart = System.currentTimeMillis();
            List<Message> context = contextEngine.prepareContext(session, turnMessages);
            ChatResponse response = modelClient.complete(context, tools);
            log.debug("Turn {} model returned in {} ms: toolCalls={}, content length={}",
                i, System.currentTimeMillis() - turnStart, response.toolCalls() != null ? response.toolCalls().size() : 0,
                response.content() != null ? response.content().length() : 0);

            if (!response.hasToolCalls()) {
                turnMessages.add(Message.assistant(response.content(), turnIndex));
                log.debug("Turn {} completed without tool calls", i);
                return new TurnResult(turnMessages, true, null);
            }

            turnMessages.add(Message.assistantToolCalls(response.toolCalls(), turnIndex));

            List<Message> toolResults = new ArrayList<>();
            for (ToolCall call : response.toolCalls()) {
                long toolStart = System.currentTimeMillis();
                ToolResult result = toolRegistry.execute(call.name(), call.id(), call.arguments(), null, session);
                log.debug("Tool {} executed in {} ms: success={}, content length={}, error={}",
                    call.name(), System.currentTimeMillis() - toolStart, result.success(),
                    result.content() != null ? result.content().length() : 0, result.error());
                toolResults.add(Message.toolResult(call.id(), formatResult(result), turnIndex));
            }
            turnMessages.addAll(toolResults);
            turnIndex++;
        }

        return TurnResult.error("Reached max turns without completion");
    }

    private String formatResult(ToolResult result) {
        if (result.success()) {
            return result.content();
        }
        return "Error: " + result.error();
    }
}
