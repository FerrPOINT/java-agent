package com.azhukov.agent.client.mcp;

import com.azhukov.agent.core.model.ToolDefinition;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpLifecycleManagerUnitTest {

    @Test
    void convertsToolDefinitionFromInputSchema() {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string", "description", "Person name")),
            "required", List.of("name")
        );

        var tool = McpSchema.Tool.builder("greet", inputSchema)
            .description("Greets a person")
            .build();

        ToolDefinition definition = McpLifecycleManager.convertToolDefinition(tool);
        assertThat(definition.name()).isEqualTo("greet");
        assertThat(definition.description()).isEqualTo("Greets a person");
    }
}
