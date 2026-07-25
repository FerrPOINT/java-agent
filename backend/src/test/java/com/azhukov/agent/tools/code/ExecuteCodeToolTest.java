package com.azhukov.agent.tools.code;

import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecuteCodeToolTest {

    @Test
    void runsPythonScriptAndReturnsOutput() {
        ExecuteCodeTool tool = new TestableExecuteCodeTool(false);

        ToolResult result = tool.execute("{\"code\":\"print(1 + 2)\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualToIgnoringWhitespace("3");
    }

    @Test
    void failsWhenCodeMissing() {
        ExecuteCodeTool tool = new ExecuteCodeTool();
        ToolResult result = tool.execute("{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Code is required");
    }

    @Test
    void failsOnExecutionError() {
        ExecuteCodeTool tool = new TestableExecuteCodeTool(true);

        ToolResult result = tool.execute("{\"code\":\"print(1)\"}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Failed to execute code");
    }

    private static class TestableExecuteCodeTool extends ExecuteCodeTool {
        private final boolean failExecution;

        TestableExecuteCodeTool(boolean failExecution) {
            this.failExecution = failExecution;
        }

        @Override
        ProcessBuilderLike createProcessBuilder(String scriptPath) {
            return new MockProcessBuilder(scriptPath, failExecution);
        }
    }

    private static class MockProcessBuilder implements ExecuteCodeTool.ProcessBuilderLike {
        private final String scriptPath;
        private final boolean failExecution;

        MockProcessBuilder(String scriptPath, boolean failExecution) {
            this.scriptPath = scriptPath;
            this.failExecution = failExecution;
        }

        @Override
        public ExecuteCodeTool.ProcessBuilderLike redirectErrorStream(boolean redirectErrorStream) {
            return this;
        }

        @Override
        public Process start() throws IOException {
            if (failExecution) {
                throw new RuntimeException("python3 not found");
            }
            String code = java.nio.file.Files.readString(java.nio.file.Path.of(scriptPath), StandardCharsets.UTF_8);
            String output = code.contains("1 + 2") ? "3\n" : "1\n";

            Process process = mock(Process.class);
            when(process.isAlive()).thenReturn(false);
            when(process.exitValue()).thenReturn(0);
            when(process.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
            try {
                when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
            } catch (InterruptedException ignored) {
            }
            return process;
        }
    }
}
