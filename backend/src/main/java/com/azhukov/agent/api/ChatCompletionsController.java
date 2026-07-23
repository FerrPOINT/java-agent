package com.azhukov.agent.api;

import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/chat/completions")
public class ChatCompletionsController {

    private final AgentRuntimeService agentRuntimeService;

    public ChatCompletionsController(AgentRuntimeService agentRuntimeService) {
        this.agentRuntimeService = agentRuntimeService;
    }

    @PostMapping
    public ChatResponseDto completions(@Valid @RequestBody ChatRequest request) {
        return agentRuntimeService.runTurn(request);
    }
}
