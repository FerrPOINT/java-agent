package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import org.springframework.stereotype.Component;

@Component
public class DefaultPromptBuilder implements PromptBuilder {

    private final AgentProperties properties;

    public DefaultPromptBuilder(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public Message buildSystemMessage(Session session) {
        String text = properties.getCore().getDefaultSystemPrompt();
        if (text == null || text.isBlank()) {
            text = "You are " + properties.getName() + ". Use available tools when needed. Be concise.";
        }
        text = text.replace("${agent.name}", properties.getName());
        return Message.system(text);
    }
}
