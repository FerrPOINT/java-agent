package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.TurnResult;

import java.util.List;

public interface AgentRuntime {

    default TurnResult runTurn(Session session, String userInput) {
        return runTurn(session, userInput, List.of());
    }

    TurnResult runTurn(Session session, String userInput, List<String> references);

    ChatResponse run(List<Message> messages, List<ToolDefinition> tools);
}
