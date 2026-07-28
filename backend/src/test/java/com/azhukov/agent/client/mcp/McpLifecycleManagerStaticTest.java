package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpLifecycleManagerStaticTest {

    @Test
    void convertToolDefinitionBuildsSchema() {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string", "description", "user name")),
            "required", List.of("name")
        );
        var tool = McpSchema.Tool.builder("greet", inputSchema).description("greets user").build();
        var def = McpLifecycleManager.convertToolDefinition("srv__greet", tool);
        assertThat(def.name()).isEqualTo("srv__greet");
        assertThat(def.description()).isEqualTo("greets user");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) def.parameters().get("properties");
        assertThat(props).containsKey("name");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) def.parameters().get("required");
        assertThat(required).contains("name");
    }
}
