package com.azhukov.agent.core.state;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DefaultAgentState implements AgentState {

    private final Map<String, String> state = new ConcurrentHashMap<>();

    @Override
    public void set(String key, String value) {
        if (value == null) {
            state.remove(key);
        } else {
            state.put(key, value);
        }
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(state.get(key));
    }

    @Override
    public Map<String, String> snapshot() {
        return Map.copyOf(state);
    }

    @Override
    public void remove(String key) {
        state.remove(key);
    }
}
