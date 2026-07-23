package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AgentRuntimeService {

    private final AgentRuntime agentRuntime;

    public AgentRuntimeService(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Transactional
    public ChatResponseDto runTurn(ChatRequest request) {
        Session session = Session.create("user-1", "openai-compatible", "qwen2.5:3b");
        TurnResult result = agentRuntime.runTurn(session, request.message());
        return new ChatResponseDto(
            UUID.randomUUID(),
            result.finalText(),
            null,
            result.completed()
        );
    }
}
