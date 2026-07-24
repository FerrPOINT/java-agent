package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class AgentController {

    private final AgentRuntimeService agentRuntimeService;
    private final AgentStreamingService streamingService;

    public AgentController(AgentRuntimeService agentRuntimeService,
                         AgentStreamingService streamingService) {
        this.agentRuntimeService = agentRuntimeService;
        this.streamingService = streamingService;
    }

    @PostMapping("/agent/chat")
    public ChatResponseDto chat(@Valid @RequestBody ChatRequest request) {
        return agentRuntimeService.runTurn(request);
    }

    @PostMapping(value = "/agent/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request) {
        return streamingService.streamTurn(request);
    }

    @PostMapping("/agent/delegate")
    public ChatResponseDto delegate(@Valid @RequestBody ChatRequest request) {
        return agentRuntimeService.runDelegate(request);
    }
}
