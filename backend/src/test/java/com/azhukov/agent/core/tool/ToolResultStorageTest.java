package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 8: Tool result persistence test.
 * Verifies large output is spilled to disk and content is truncated in-context.
 */
class ToolResultStorageTest {

    private ToolResultStorage storage(AgentProperties props) {
        return new ToolResultStorage(props);
    }

    @Test
    void smallContentReturnedUnchanged() {
        AgentProperties props = new AgentProperties();
        ToolResultStorage store = storage(props);

        String content = "Hello, world!";
        String result = store.maybePersist(content, "test_tool", "call_1");
        assertThat(result).isEqualTo(content);
    }

    @Test
    void largeContentPersistedToDisk() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setPersistThresholdBytes(100); // Small threshold for testing
        ToolResultStorage store = storage(props);

        // Use content larger than PREVIEW_CHARS (2000) so truncation occurs
        String largeContent = "A".repeat(5000);
        String result = store.maybePersist(largeContent, "test_tool", "call_2");

        assertThat(result).contains("[Full output saved to");
        assertThat(result).contains("Preview");
        assertThat(result).contains("[truncated]");
        assertThat(result).isNotEqualTo(largeContent);
    }

    @Test
    void persistedResultContainsFilePath() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setPersistThresholdBytes(100);
        ToolResultStorage store = storage(props);

        String content = "B".repeat(5000);
        String result = store.maybePersist(content, "test_tool", "call_3");

        assertThat(result).contains("java-agent-results");
        assertThat(result).contains(".txt");
    }

    @Test
    void nullContentReturnedUnchanged() {
        AgentProperties props = new AgentProperties();
        ToolResultStorage store = storage(props);

        String result = store.maybePersist((String) null, "test_tool", "call_4");
        assertThat(result).isNull();
    }

    @Test
    void emptyContentReturnedUnchanged() {
        AgentProperties props = new AgentProperties();
        ToolResultStorage store = storage(props);

        String result = store.maybePersist("", "test_tool", "call_5");
        assertThat(result).isEmpty();
    }

    @Test
    void toolResultSuccessPersisted() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setPersistThresholdBytes(100);
        ToolResultStorage store = storage(props);

        ToolResult large = ToolResult.ok("C".repeat(5000));
        ToolResult result = store.maybePersist(large, "test_tool", "call_6");

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("[Full output saved to");
    }

    @Test
    void toolResultFailureNotPersisted() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setPersistThresholdBytes(100);
        ToolResultStorage store = storage(props);

        ToolResult failure = ToolResult.fail("E".repeat(5000));
        ToolResult result = store.maybePersist(failure, "test_tool", "call_7");

        // Error results should not be persisted
        assertThat(result.content()).doesNotContain("[Full output saved to");
    }

    @Test
    void turnBudgetEnforcementPersistsLargeResults() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setPersistThresholdBytes(100);
        props.getToolOutput().setTurnBudgetBytes(200);
        ToolResultStorage store = storage(props);

        List<String> contents = List.of("X".repeat(150), "Y".repeat(150));
        List<String> ids = List.of("id1", "id2");

        List<String> result = store.enforceTurnBudget(contents, ids);

        // Total was 300, budget is 200, so at least one should be persisted
        assertThat(result).anyMatch(c -> c.contains("[Full output saved to"));
    }

    @Test
    void turnBudgetNoChangeWhenUnderBudget() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setTurnBudgetBytes(10000);
        ToolResultStorage store = storage(props);

        List<String> contents = List.of("small1", "small2");
        List<String> ids = List.of("id1", "id2");

        List<String> result = store.enforceTurnBudget(contents, ids);

        assertThat(result).isEqualTo(contents);
    }
}