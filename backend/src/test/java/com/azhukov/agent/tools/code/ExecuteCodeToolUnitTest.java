package com.azhukov.agent.tools.code;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExecuteCodeTool that don't require a real Python interpreter.
 * Uses a mock ProcessBuilderLike to test branch coverage.
 */
class ExecuteCodeToolUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Session session = Session.create("u", "p", "m");

    @Test
    void failsWhenCodeIsBlank() throws Exception {
        ExecuteCodeTool tool = new ExecuteCodeTool();
        ToolResult r = tool.execute("{\"code\":\"\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(json(r).get("error").asText()).contains("No code provided");
        assertThat(r.error()).contains("No code provided");
    }

    @Test
    void failsWhenCodeIsNull() throws Exception {
        ExecuteCodeTool tool = new ExecuteCodeTool();
        ToolResult r = tool.execute("{}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(json(r).get("error").asText()).contains("No code provided");
        assertThat(r.error()).contains("No code provided");
    }

    @Test
    void failsActionablyWhenCodeIsNotAString() throws Exception {
        ExecuteCodeTool tool = new ExecuteCodeTool();
        ToolResult r = tool.execute("{\"code\":{\"script\":\"print(1)\"}}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(json(r).get("error").asText()).contains("execute_code received an object in 'code'");
        assertThat(json(r).get("error").asText()).contains("requires Python source as a string");
        assertThat(r.error()).contains("requires Python source as a string");
    }

    @Test
    void failsWithJsonWhenArgumentsAreInvalid() throws Exception {
        ExecuteCodeTool tool = new ExecuteCodeTool();
        ToolResult r = tool.execute("not-json", null, session);
        assertThat(r.success()).isFalse();
        assertThat(json(r).get("error").asText()).contains("Invalid tool arguments");
        assertThat(r.error()).contains("Invalid tool arguments");
    }

    @Test
    void failsActionablyWhenModeIsNotAString() throws Exception {
        ExecuteCodeTool tool = new ExecuteCodeTool();
        ToolResult r = tool.execute("{\"code\":\"print(1)\",\"mode\":{\"name\":\"local\"}}", null, session);

        assertThat(r.success()).isFalse();
        assertThat(json(r).get("error").asText()).contains("received an object in 'mode'");
        assertThat(json(r).get("error").asText()).contains("Supported mode in java-agent: local");
    }

    @Test
    void explicitLocalModeRunsPerCallAndReportsModeMetadata() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());
        stubSuccessfulProcess(tool, "ok", 0);

        ToolResult r = tool.execute("{\"code\":\"print('ok')\",\"mode\":\"local\"}", null, session);

        assertThat(r.success()).isTrue();
        JsonNode response = json(r);
        assertThat(response.get("status").asText()).isEqualTo("success");
        assertThat(response.get("execution_mode").asText()).isEqualTo("local");
        assertThat(response.get("kernel_mode").asText()).isEqualTo("per_call");
        assertThat(response.has("reset_ignored")).isFalse();
    }

    @Test
    void executionModeAliasRunsLocalMode() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());
        stubSuccessfulProcess(tool, "ok", 0);

        ToolResult r = tool.execute("{\"code\":\"print('ok')\",\"execution_mode\":\"per-call\"}", null, session);

        assertThat(r.success()).isTrue();
        assertThat(json(r).get("execution_mode").asText()).isEqualTo("local");
        assertThat(json(r).get("kernel_mode").asText()).isEqualTo("per_call");
    }

    @Test
    void resetTrueInLocalPerCallIsReportedAsIgnored() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());
        stubSuccessfulProcess(tool, "fresh", 0);

        ToolResult r = tool.execute("{\"code\":\"print('fresh')\",\"reset\":true}", null, session);

        assertThat(r.success()).isTrue();
        JsonNode response = json(r);
        assertThat(response.get("execution_mode").asText()).isEqualTo("local");
        assertThat(response.get("kernel_mode").asText()).isEqualTo("per_call");
        assertThat(response.get("reset_ignored").asBoolean()).isTrue();
        assertThat(response.get("reset_reason").asText()).contains("no persistent kernel state exists");
    }

    @Test
    void sessionKernelModeFailsWithoutStartingProcess() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        ToolResult r = tool.execute("{\"code\":\"x = 41\",\"mode\":\"session_kernel\"}", null, session);

        assertThat(r.success()).isFalse();
        JsonNode response = json(r);
        assertThat(response.get("status").asText()).isEqualTo("error");
        assertThat(response.get("execution_mode").asText()).isEqualTo("session_kernel");
        assertThat(response.get("kernel_mode").asText()).isEqualTo("session");
        assertThat(response.get("supported_modes").get(0).asText()).isEqualTo("local");
        assertThat(response.get("error").asText()).contains("requires Hermes session-persistent kernel runtime");
        verify(tool, never()).createProcessBuilder(anyString());
    }

    @Test
    void remoteRpcModeFailsWithoutStartingProcess() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        ToolResult r = tool.execute("{\"code\":\"print(1)\",\"mode\":\"remote_rpc\"}", null, session);

        assertThat(r.success()).isFalse();
        JsonNode response = json(r);
        assertThat(response.get("status").asText()).isEqualTo("error");
        assertThat(response.get("execution_mode").asText()).isEqualTo("remote_rpc");
        assertThat(response.get("error").asText()).contains("requires Hermes terminal-environment file-based RPC runtime");
        verify(tool, never()).createProcessBuilder(anyString());
    }

    @Test
    void unknownModeFailsWithoutFallingBackToLocal() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        ToolResult r = tool.execute("{\"code\":\"print(1)\",\"mode\":\"strict\"}", null, session);

        assertThat(r.success()).isFalse();
        JsonNode response = json(r);
        assertThat(response.get("status").asText()).isEqualTo("error");
        assertThat(response.get("execution_mode").asText()).isEqualTo("strict");
        assertThat(response.get("error").asText()).contains("Unsupported execute_code mode 'strict'");
        verify(tool, never()).createProcessBuilder(anyString());
    }

    @Test
    void parsesTimeoutFromStringStripsNonNumericAndUsesParsedValue() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        long[] capturedTimeout = {-1};
        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            Process mockProcess = mock(Process.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            when(pbl.start()).thenReturn(mockProcess);
            when(mockProcess.waitFor(anyLong(), any())).thenAnswer(w -> {
                capturedTimeout[0] = w.getArgument(0);
                return true;
            });
            when(mockProcess.getInputStream()).thenReturn(
                new ByteArrayInputStream("ok".getBytes()));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        // "45s" → strip "s" → parseInt("45") → 45 seconds
        ToolResult r = tool.execute("{\"code\":\"print(1)\",\"timeout\":\"45s\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(capturedTimeout[0]).isEqualTo(45L);
    }

    @Test
    void parsesNumericTimeoutUsesExactValue() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        long[] capturedTimeout = {-1};
        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            Process mockProcess = mock(Process.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            when(pbl.start()).thenReturn(mockProcess);
            when(mockProcess.waitFor(anyLong(), any())).thenAnswer(w -> {
                capturedTimeout[0] = w.getArgument(0);
                return true;
            });
            when(mockProcess.getInputStream()).thenReturn(
                new ByteArrayInputStream("ok".getBytes()));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        // "120" → 120 seconds
        ToolResult r = tool.execute("{\"code\":\"print(1)\",\"timeout\":\"120\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(capturedTimeout[0]).isEqualTo(120L);
    }

    @Test
    void timeoutWithNonNumericStringFallsBackToDefault300() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        long[] capturedTimeout = {-1};
        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            Process mockProcess = mock(Process.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            when(pbl.start()).thenReturn(mockProcess);
            when(mockProcess.waitFor(anyLong(), any())).thenAnswer(w -> {
                capturedTimeout[0] = w.getArgument(0);
                return true;
            });
            when(mockProcess.getInputStream()).thenReturn(
                new ByteArrayInputStream("ok".getBytes()));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        // "not-a-number" → replaceAll("[^0-9]", "") → "" → parseInt fails → fallback to 300
        ToolResult r = tool.execute("{\"code\":\"print(1)\",\"timeout\":\"not-a-number\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(capturedTimeout[0]).isEqualTo(300L);
    }

    @Test
    void timeoutWithMixedStringExtractsDigitsOnly() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        long[] capturedTimeout = {-1};
        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            Process mockProcess = mock(Process.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            when(pbl.start()).thenReturn(mockProcess);
            when(mockProcess.waitFor(anyLong(), any())).thenAnswer(w -> {
                capturedTimeout[0] = w.getArgument(0);
                return true;
            });
            when(mockProcess.getInputStream()).thenReturn(
                new ByteArrayInputStream("ok".getBytes()));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        // "abc60xyz" → replaceAll strips letters → "60" → 60 seconds
        ToolResult r = tool.execute("{\"code\":\"print(1)\",\"timeout\":\"abc60xyz\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(capturedTimeout[0]).isEqualTo(60L);
    }

    @Test
    void runPythonReturnsSuccessWithMockedProcess() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        // Mock the createProcessBuilder to return a fake that produces output
        doAnswer(inv -> {
            String scriptPath = inv.getArgument(0);
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            Process mockProcess = mock(Process.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            when(pbl.start()).thenReturn(mockProcess);
            when(mockProcess.waitFor(anyLong(), any())).thenReturn(true);
            when(mockProcess.getInputStream()).thenReturn(
                new ByteArrayInputStream("hello from python".getBytes()));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        ToolResult r = tool.execute("{\"code\":\"print('hello')\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("hello from python");
    }

    @Test
    void runPythonReturnsTimeoutWithMockedProcess() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            Process mockProcess = mock(Process.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            when(pbl.start()).thenReturn(mockProcess);
            // Simulate timeout — process doesn't finish in time
            when(mockProcess.waitFor(anyLong(), any())).thenReturn(false);
            when(mockProcess.getInputStream()).thenReturn(
                new ByteArrayInputStream("started".getBytes()));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        ToolResult r = tool.execute("{\"code\":\"import time; time.sleep(99)\",\"timeout\":\"1\"}", null, session);
        assertThat(r.success()).isFalse();
        JsonNode response = json(r);
        assertThat(response.get("status").asText()).isEqualTo("timeout");
        assertThat(response.get("error").asText()).contains("timed out");
        assertThat(r.error()).contains("timed out");
        assertThat(response.get("output").asText()).contains("started");
        // Verify the error message includes the actual parsed timeout value
        assertThat(response.get("error").asText()).contains("1 seconds");
    }

    @Test
    void runPythonReturnsErrorOnIOException() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            when(pbl.start()).thenThrow(new IOException("permission denied"));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        ToolResult r = tool.execute("{\"code\":\"print(1)\"}", null, session);
        assertThat(r.success()).isFalse();
        JsonNode response = json(r);
        assertThat(response.get("status").asText()).isEqualTo("error");
        assertThat(response.get("error").asText()).contains("Failed to execute code");
        assertThat(r.error()).contains("Failed to execute code");
    }

    @Test
    void runPythonReturnsErrorOnGenericException() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            Process mockProcess = mock(Process.class);
            when(pbl.start()).thenReturn(mockProcess);
            when(mockProcess.waitFor(anyLong(), any())).thenReturn(true);
            // Throw when reading input stream
            when(mockProcess.getInputStream()).thenThrow(new RuntimeException("stream broken"));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        ToolResult r = tool.execute("{\"code\":\"print(1)\"}", null, session);
        assertThat(r.success()).isFalse();
        JsonNode response = json(r);
        assertThat(response.get("status").asText()).isEqualTo("error");
        assertThat(response.get("error").asText()).contains("Failed to execute code");
        assertThat(r.error()).contains("Failed to execute code");
    }

    @Test
    void runPythonReturnsErrorOnNonZeroExitCode() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            Process mockProcess = mock(Process.class);
            when(pbl.start()).thenReturn(mockProcess);
            when(mockProcess.waitFor(anyLong(), any())).thenReturn(true);
            when(mockProcess.exitValue()).thenReturn(1);
            when(mockProcess.getInputStream()).thenReturn(
                new ByteArrayInputStream("Traceback (most recent call last):\nValueError: bad value".getBytes()));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        ToolResult r = tool.execute("{\"code\":\"raise ValueError('bad value')\"}", null, session);
        assertThat(r.success()).isFalse();
        JsonNode response = json(r);
        assertThat(response.get("status").asText()).isEqualTo("error");
        assertThat(response.get("error").asText()).isEqualTo("Script exited with code 1");
        assertThat(response.get("output").asText()).contains("ValueError");
        assertThat(r.error()).isEqualTo("Script exited with code 1");
    }

    @Test
    void runPythonStripsAnsiFromOutput() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            Process mockProcess = mock(Process.class);
            when(pbl.start()).thenReturn(mockProcess);
            when(mockProcess.waitFor(anyLong(), any())).thenReturn(true);
            when(mockProcess.exitValue()).thenReturn(0);
            when(mockProcess.getInputStream()).thenReturn(
                new ByteArrayInputStream("\u001B[31mred\u001B[0m".getBytes()));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        ToolResult r = tool.execute("{\"code\":\"print('red')\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(json(r).get("output").asText()).isEqualTo("red");
        assertThat(json(r).get("output").asText()).doesNotContain("\u001B");
    }

    @Test
    void runPythonTruncatesLargeStdout() throws Exception {
        ExecuteCodeTool tool = spy(new ExecuteCodeTool());

        // Generate >50KB of output
        StringBuilder largeOutput = new StringBuilder();
        for (int i = 0; i < 60000; i++) {
            largeOutput.append("x");
        }
        String bigOutput = largeOutput.toString();

        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            Process mockProcess = mock(Process.class);
            when(pbl.start()).thenReturn(mockProcess);
            when(mockProcess.waitFor(anyLong(), any())).thenReturn(true);
            when(mockProcess.exitValue()).thenReturn(0);
            when(mockProcess.getInputStream()).thenReturn(
                new ByteArrayInputStream(bigOutput.getBytes()));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        ToolResult r = tool.execute("{\"code\":\"print('x'*60000)\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(json(r).get("output").asText()).contains("bytes omitted");
        assertThat(json(r).get("output").asText().length()).isLessThan(bigOutput.length());
    }

    @Test
    void scrubChildEnvironmentDropsSecretLikeVariablesAndKeepsRuntimeEssentials() {
        ExecuteCodeTool tool = new ExecuteCodeTool();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/usr/bin");
        env.put("HOME", "/home/user");
        env.put("TMPDIR", "/tmp");
        env.put("HERMES_HOME", "/home/user/.hermes");
        env.put("SYSTEMROOT", "C:\\Windows");
        env.put("AGENT_MODEL_API_KEY", "secret");
        env.put("OPENAI_API_TOKEN", "secret");
        env.put("DATABASE_DSN", "postgres://secret");
        env.put("HERMES_WEBHOOK_URL", "https://hooks.example/secret");
        env.put("CUSTOM_SETTING", "visible-but-not-needed");
        env.put("PYTHONIOENCODING", "cp1251");

        tool.scrubChildEnvironment(env);

        assertThat(env)
            .containsEntry("PATH", "/usr/bin")
            .containsEntry("HOME", "/home/user")
            .containsEntry("TMPDIR", "/tmp")
            .containsEntry("HERMES_HOME", "/home/user/.hermes")
            .containsEntry("SYSTEMROOT", "C:\\Windows")
            .containsEntry("PYTHONIOENCODING", "utf-8")
            .containsEntry("PYTHONUTF8", "1")
            .containsEntry("PYTHONDONTWRITEBYTECODE", "1");
        assertThat(env)
            .doesNotContainKeys(
                "AGENT_MODEL_API_KEY",
                "OPENAI_API_TOKEN",
                "DATABASE_DSN",
                "HERMES_WEBHOOK_URL",
                "CUSTOM_SETTING"
            );
    }

    private JsonNode json(ToolResult result) throws Exception {
        return MAPPER.readTree(result.content());
    }

    private void stubSuccessfulProcess(ExecuteCodeTool tool, String output, int exitCode) throws Exception {
        doAnswer(inv -> {
            ExecuteCodeTool.ProcessBuilderLike pbl = mock(ExecuteCodeTool.ProcessBuilderLike.class);
            Process mockProcess = mock(Process.class);
            when(pbl.redirectErrorStream(true)).thenReturn(pbl);
            when(pbl.start()).thenReturn(mockProcess);
            when(mockProcess.waitFor(anyLong(), any())).thenReturn(true);
            when(mockProcess.exitValue()).thenReturn(exitCode);
            when(mockProcess.getInputStream()).thenReturn(
                new ByteArrayInputStream(output.getBytes()));
            return pbl;
        }).when(tool).createProcessBuilder(anyString());
    }
}
