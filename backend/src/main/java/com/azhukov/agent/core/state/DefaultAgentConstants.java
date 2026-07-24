package com.azhukov.agent.core.state;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DefaultAgentConstants implements AgentConstants {

    private final Map<String, String> constants = new ConcurrentHashMap<>();

    public DefaultAgentConstants() {
        constants.put("agent.name", System.getProperty("agent.name", "Джава агент"));
        constants.put("agent.version", "1.0.0");
    }

    @Override
    public Map<String, String> constants() {
        return Map.copyOf(constants);
    }

    @Override
    public String resolve(String key) {
        return constants.getOrDefault(key, "");
    }
}
