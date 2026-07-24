package com.azhukov.agent.core.state;

import java.util.Map;
import java.util.Optional;

public interface AgentState {

    void set(String key, String value);

    Optional<String> get(String key);

    Map<String, String> snapshot();

    void remove(String key);
}
