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

        // Use content larger than PREVIEW_CHARS (1500) so preview truncation occurs
        String largeContent = "A".repeat(5000);
        String result = store.maybePersist(largeContent, "test_tool", "call_2");

        assertThat(result).contains("<persisted-output>");
        assertThat(result).contains("Full output saved to:");
        assertThat(result).contains("Use the read_file tool with offset and limit");
        assertThat(result).contains("Preview");
        assertThat(result).contains("</persisted-output>");
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
    void readFileResultIsPinnedAndNeverPersisted() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setPersistThresholdBytes(10);
        ToolResultStorage store = storage(props);

        String content = "R".repeat(5000);
        String result = store.maybePersist(content, "read_file", "call_read");

        assertThat(result).isEqualTo(content);
        assertThat(result).doesNotContain("<persisted-output>");
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
        assertThat(result.content()).contains("<persisted-output>");
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
        assertThat(result).anyMatch(c -> c.contains("<persisted-output>"));
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


    @Test
    void unicodeContentUsesUtf8ByteThresholdNotCharacterCount() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setPersistThresholdBytes(10);
        ToolResultStorage store = storage(props);

        // Four Cyrillic chars are 8 UTF-8 bytes; five are 10. Six = 12 bytes.
        String content = "яяяяяя";
        String result = store.maybePersist(content, "test_tool", "unicode-call");

        assertThat(result).contains("<persisted-output>");
    }

    @Test
    void hostileToolCallIdCannotControlPersistedPath() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setPersistThresholdBytes(10);
        ToolResultStorage store = storage(props);

        String result = store.maybePersist("A".repeat(100), "test_tool", "../../outside.txt");

        assertThat(result).contains("<persisted-output>");
        assertThat(result).doesNotContain("outside.txt");
        assertThat(result).doesNotContain("../");
    }

}
