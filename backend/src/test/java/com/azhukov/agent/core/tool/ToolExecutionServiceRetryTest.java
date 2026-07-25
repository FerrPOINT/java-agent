package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("noop")
class ToolExecutionServiceRetryTest {

    @Autowired
    private ToolExecutionService toolExecutionService;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AgentProperties properties;

    @Test
    void retrySucceedsAfterTransientFailures() {
        AtomicInteger counter = new AtomicInteger(0);
        String toolName = "flaky-test-tool";
        ToolDefinition def = new ToolDefinition(toolName, "flaky", java.util.Map.of("type","object","properties",java.util.Map.of(),"required",java.util.List.of()));
        toolRegistry.registerDynamic(toolName, def, (args, lastAssistant, session) -> {
            int attempt = counter.incrementAndGet();
            if (attempt < 3) {
                throw new RuntimeException(" simulated failure attempt " + attempt);
            }
            return ToolResult.ok("ok-after-retry");
        });

        Session session = Session.create("test", "noop", "");
        ToolResult result = toolExecutionService.execute(toolName, "call-1", "{}", null, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("ok-after-retry");
        assertThat(counter.get()).isEqualTo(3);
    }
}
