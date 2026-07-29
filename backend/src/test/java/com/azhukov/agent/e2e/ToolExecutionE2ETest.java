package com.azhukov.agent.e2e;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-2: Tool execution integration test.
 * Verifies that a tool registered dynamically in the Spring context can be
 * invoked through {@link ToolExecutionService} and that the result is captured.
 */
@SpringBootTest
@ActiveProfiles("noop")
@Tag("slow")
class ToolExecutionE2ETest {

    @Autowired
    private ToolExecutionService toolExecutionService;

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void dynamicToolExecutesThroughService() {
        String toolName = "e2e-echo-tool";
        ToolDefinition definition = new ToolDefinition(
            toolName,
            "Echoes input for E2E verification",
            Map.of(
                "type", "object",
                "properties", Map.of("text", Map.of("type", "string")),
                "required", java.util.List.of("text")
            )
        );

        toolRegistry.registerDynamic(toolName, definition, (args, lastAssistant, session) -> {
            String text = args.replaceAll(".*\"text\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            return ToolResult.ok("echo: " + text);
        });

        Session session = Session.create("test-user", "noop", "");
        ToolResult result = toolExecutionService.execute(toolName, "call-1", "{\"text\":\"hello\"}", null, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("echo: hello");
    }

    @Test
    void readFileToolExecutesThroughService() {
        Session session = Session.create("test-user", "noop", "");
        ToolResult result = toolExecutionService.execute(
            "read_file",
            "call-2",
            "{\"path\":\"/opt/dev/java-agent/README.md\",\"limit\":5}",
            null,
            session
        );

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isNotBlank();
        assertThat(result.content()).contains("Java Agent");
    }

    @Test
    void unknownToolReturnsFailure() {
        Session session = Session.create("test-user", "noop", "");
        ToolResult result = toolExecutionService.execute("unknown_tool", "call-3", "{}", null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown tool");
    }

    @Test
    void toolRegistryListsCoreTools() {
        assertThat(toolRegistry.getDefinitions()).extracting(ToolDefinition::name)
            .contains("read_file", "write_file", "terminal");
    }
}
