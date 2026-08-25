package com.azhukov.agent.tools.code;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExecuteCodeTool that don't require a real Python interpreter.
 * Uses a mock ProcessBuilderLike to test branch coverage.
 */
class ExecuteCodeToolUnitTest {

    private final Session session = Session.create("u", "p", "m");

    @Test
    void failsWhenCodeIsBlank() {
        ExecuteCodeTool tool = new ExecuteCodeTool();
        ToolResult r = tool.execute("{\"code\":\"\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("execute_code requires Python source");
    }

    @Test
    void failsWhenCodeIsNull() {
        ExecuteCodeTool tool = new ExecuteCodeTool();
        ToolResult r = tool.execute("{}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("execute_code requires Python source");
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
            return pbl;
        }).when(tool).createProcessBuilder(anyString());

        ToolResult r = tool.execute("{\"code\":\"import time; time.sleep(99)\",\"timeout\":\"1\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("timed out");
        // Verify the error message includes the actual parsed timeout value
        assertThat(r.error()).contains("1 seconds");
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
        assertThat(r.error()).contains("Failed to execute code");
    }
}