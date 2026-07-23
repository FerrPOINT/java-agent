package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;

public interface AgentRuntime {

    TurnResult runTurn(Session session, String userInput);
}
