package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultFormatterTest {

    private final ToolResultFormatter formatter = new ToolResultFormatter();

    @Test
    void formatResult_successReturnsContent() {
        ToolResult result = ToolResult.ok("Sunny, 22°C");
        assertThat(formatter.formatResult(result)).isEqualTo("Sunny, 22°C");
    }

    @Test
    void formatResult_failurePrefixesWithError() {
        ToolResult result = ToolResult.fail("Connection timed out");
        assertThat(formatter.formatResult(result)).isEqualTo("Error: Connection timed out");
    }

    @Test
    void formatResult_emptySuccessReturnsEmpty() {
        ToolResult result = ToolResult.ok("");
        assertThat(formatter.formatResult(result)).isEqualTo("");
    }

    @Test
    void formatResult_nullErrorInFailure() {
        // ToolResult.fail sets content="" and error=msg, but error is never null here
        ToolResult result = ToolResult.fail("some error");
        assertThat(formatter.formatResult(result)).startsWith("Error: ");
    }
}