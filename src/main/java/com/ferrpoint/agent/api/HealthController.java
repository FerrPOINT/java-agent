package com.ferrpoint.agent.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ferrpoint.agent.config.AgentProperties;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final AgentProperties properties;

    public HealthController(AgentProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
            "status", "up",
            "name", properties.getName()
        );
    }
}
