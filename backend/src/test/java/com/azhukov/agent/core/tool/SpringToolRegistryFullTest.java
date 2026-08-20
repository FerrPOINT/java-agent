package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig
class SpringToolRegistryFullTest {

    @Configuration
    @Import({SpringToolRegistry.class, ManagedToolGate.class})
    static class Config {
        @Bean
        AgentProperties agentProperties() {
            return new AgentProperties();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        EchoTool echoTool() {
            return new EchoTool();
        }

        @Bean
        SumTool sumTool() {
            return new SumTool();
        }
    }

    @AgentTool(name = "echo", description = "echo tool", toolset = "core")
    static class EchoTool implements ToolHandler {
        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session) {
            return ToolResult.ok(arguments);
        }
    }

    @AgentTool(name = "sum", description = "sum two ints", toolset = "math")
    static class SumTool implements ToolHandler {
        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session) {
            Args args = ToolHandler.parseJson(arguments, Args.class);
            return ToolResult.ok(String.valueOf(args.a() + args.b()));
        }

        public record Args(
            @ToolParam(description = "first value") int a,
            @ToolParam(description = "second value") int b
        ) {}
    }

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void registersBeansWithAgentToolAnnotation() {
        List<ToolDefinition> definitions = toolRegistry.getDefinitions();
        assertThat(definitions).hasSize(2);
        assertThat(definitions.stream().map(ToolDefinition::name)).containsExactlyInAnyOrder("echo", "sum");
    }

    @Test
    void filtersDefinitionsByToolset() {
        assertThat(toolRegistry.getDefinitions(Set.of("core"))).hasSize(1);
        assertThat(toolRegistry.getDefinitions(Set.of("core")).get(0).name()).isEqualTo("echo");
        assertThat(toolRegistry.getDefinitions(Set.of("math"))).hasSize(1);
        assertThat(toolRegistry.getDefinitions(Set.of("math")).get(0).name()).isEqualTo("sum");
    }

    @Test
    void emptyToolsetFilterReturnsAll() {
        assertThat(toolRegistry.getDefinitions(Set.of())).hasSize(2);
    }

    @Test
    void executeKnownToolReturnsResult() {
        ToolResult result = toolRegistry.execute("echo", "id-1", "hello", null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("hello");
    }

    @Test
    void executeUnknownToolReturnsFail() {
        ToolResult result = toolRegistry.execute("missing", "id-1", "x", null, null);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown tool");
    }

    @Test
    void getToolsetsReturnsUniqueToolsets() {
        assertThat(toolRegistry.getToolsets()).containsExactlyInAnyOrder("core", "math");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void registerDynamicAddsTool() {
        toolRegistry.registerDynamic("dynamic", new ToolDefinition("dynamic", "d", Map.of()),
            (args, last, session) -> ToolResult.ok("dyn:" + args));
        List<ToolDefinition> all = toolRegistry.getDefinitions();
        assertThat(all).hasSize(3);
        ToolResult result = toolRegistry.execute("dynamic", "id", "x", null, null);
        assertThat(result.content()).isEqualTo("dyn:x");
    }

    @Test
    void definitionIncludesParameters() {
        ToolDefinition sum = toolRegistry.getDefinitions().stream()
            .filter(d -> d.name().equals("sum"))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) sum.parameters().get("properties");
        assertThat(params).containsKeys("a", "b");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) sum.parameters().get("required");
        assertThat(required).contains("a", "b");
    }
}
