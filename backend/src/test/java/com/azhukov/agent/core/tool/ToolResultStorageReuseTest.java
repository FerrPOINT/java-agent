package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * h42: Tests for tool_call_id reuse in ToolResultStorage.
 * When an MCP server reuses the same tool_call_id for different calls,
 * keep all tool results instead of overwriting/dropping.
 */
class ToolResultStorageReuseTest {

    @Test
    void storeResult_preservesAllResultsForSameToolCallId() {
        var storage = new ToolResultStorage(new AgentProperties());
        String callId = "call-123";

        ToolResult result1 = ToolResult.ok("first result");
        ToolResult result2 = ToolResult.ok("second result");
        ToolResult result3 = ToolResult.ok("third result");

        storage.storeResult(callId, result1);
        storage.storeResult(callId, result2);
        storage.storeResult(callId, result3);

        var results = storage.getResults(callId);
        assertThat(results).hasSize(3);
        assertThat(results.get(0).content()).isEqualTo("first result");
        assertThat(results.get(1).content()).isEqualTo("second result");
        assertThat(results.get(2).content()).isEqualTo("third result");
    }

    @Test
    void storeResult_preservesInsertionOrder() {
        var storage = new ToolResultStorage(new AgentProperties());
        String callId = "reused-id";

        for (int i = 0; i < 5; i++) {
            storage.storeResult(callId, ToolResult.ok("result-" + i));
        }

        var results = storage.getResults(callId);
        assertThat(results).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(results.get(i).content()).isEqualTo("result-" + i);
        }
    }

    @Test
    void storeResult_nullCallId_ignored() {
        var storage = new ToolResultStorage(new AgentProperties());
        storage.storeResult(null, ToolResult.ok("test"));
        assertThat(storage.hasResults(null)).isFalse();
    }

    @Test
    void storeResult_nullResult_ignored() {
        var storage = new ToolResultStorage(new AgentProperties());
        storage.storeResult("call-1", null);
        assertThat(storage.hasResults("call-1")).isFalse();
    }

    @Test
    void getResults_emptyForUnknownId() {
        var storage = new ToolResultStorage(new AgentProperties());
        assertThat(storage.getResults("unknown-id")).isEmpty();
    }

    @Test
    void getResults_emptyForNullId() {
        var storage = new ToolResultStorage(new AgentProperties());
        assertThat(storage.getResults(null)).isEmpty();
    }

    @Test
    void hasResults_trueAfterStore() {
        var storage = new ToolResultStorage(new AgentProperties());
        storage.storeResult("call-1", ToolResult.ok("test"));
        assertThat(storage.hasResults("call-1")).isTrue();
        assertThat(storage.hasResults("call-2")).isFalse();
    }

    @Test
    void clearResults_removesAllResultsForId() {
        var storage = new ToolResultStorage(new AgentProperties());
        storage.storeResult("call-1", ToolResult.ok("a"));
        storage.storeResult("call-1", ToolResult.ok("b"));
        storage.storeResult("call-2", ToolResult.ok("c"));

        storage.clearResults("call-1");

        assertThat(storage.hasResults("call-1")).isFalse();
        assertThat(storage.getResults("call-1")).isEmpty();
        assertThat(storage.hasResults("call-2")).isTrue();
    }

    @Test
    void storeResult_failureResultsAlsoPreserved() {
        var storage = new ToolResultStorage(new AgentProperties());
        String callId = "call-fail";

        storage.storeResult(callId, ToolResult.ok("first ok"));
        storage.storeResult(callId, ToolResult.fail("error 1"));
        storage.storeResult(callId, ToolResult.fail("error 2"));

        var results = storage.getResults(callId);
        assertThat(results).hasSize(3);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(1).success()).isFalse();
        assertThat(results.get(2).success()).isFalse();
        assertThat(results.get(1).error()).isEqualTo("error 1");
    }
}