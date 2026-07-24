package com.azhukov.agent.core.state;

import java.util.Map;

public interface AgentConstants {

    Map<String, String> constants();

    String resolve(String key);
}
