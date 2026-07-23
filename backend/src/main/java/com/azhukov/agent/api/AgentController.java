package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.service.AgentRuntimeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AgentController {

    private final AgentRuntimeService agentRuntimeService;

    public AgentController(AgentRuntimeService agentRuntimeService) {
        this.agentRuntimeService = agentRuntimeService;
    }

    @PostMapping("/agent/chat")
    public ChatResponseDto chat(@Valid @RequestBody ChatRequest request) {
        return agentRuntimeService.runTurn(request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handle(Exception e) {
        return ResponseEntity.status(500).body(Map.of(
            "error", e.getMessage(),
            "type", e.getClass().getName(),
            "trace", getTrace(e)
        ));
    }

    private String getTrace(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement el : e.getStackTrace()) {
            if (sb.length() > 2000) break;
            sb.append(el.toString()).append("\n");
        }
        return sb.toString();
    }
}
