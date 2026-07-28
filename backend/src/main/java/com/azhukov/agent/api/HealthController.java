package com.azhukov.agent.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private final AgentProperties properties;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
            "status", "up",
            "name", properties.getName()
        );
    }
}
