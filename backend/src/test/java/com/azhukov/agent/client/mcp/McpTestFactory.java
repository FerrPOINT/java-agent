package com.azhukov.agent.client.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;

/** Unit-test factory matching the McpLifecycleManager constructor arity. */
final class McpTestFactory {

    private McpTestFactory() {
    }

    static McpLifecycleManager create(
            com.azhukov.agent.config.AgentProperties properties,
            ObjectProvider<com.azhukov.agent.service.McpConfigStore> storeProvider) {
        return new McpLifecycleManager(
            properties, new ObjectMapper(), null, null, null, null, null, null, storeProvider);
    }
}
