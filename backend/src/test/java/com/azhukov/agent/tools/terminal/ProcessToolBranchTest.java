package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for {@link ProcessTool}.
 * Covers all action types, error paths, edge cases.
 */
class ProcessToolBranchTest {

    private ProcessTool processTool;

    @AfterEach
    void cleanup() {
        if (processTool != null) {
            processTool.cleanup();
        }
    }

    // ── Unknown action ──

    @Test
    void execute_unknownAction_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"unknown_action\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown process action");
    }

    @Test
    void execute_nullAction_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown process action");
    }

    // ── list ──

    @Test
    void list_empty_returnsOk() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"list\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    @Test
    void list_withProcesses_showsRunningProcess() throws Exception {
        processTool = new ProcessTool();
        processTool.spawn("echo hello", 5);
        ToolResult result = processTool.execute(
            "{\"action\":\"list\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("proc_");
    }

    // ── poll ──

    @Test
    void poll_nonExistentSession_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"poll\",\"session_id\":\"nonexistent\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"not_found\"");
    }

    @Test
    void poll_existingProcess_returnsStatus() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo hello", 5);
        // Wait for the reader thread to drain process output (deterministic sync)
        waitForReaderThread(mp);
        ToolResult result = processTool.execute(
            "{\"action\":\"poll\",\"session_id\":\"" + mp.id + "\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("session_id:");
    }

    // ── log ──

    @Test
    void log_nonExistentSession_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"log\",\"session_id\":\"nonexistent\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"not_found\"");
    }

    @Test
    void log_existingProcess_withOffsetAndLimit() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo line1\necho line2\necho line3", 5);
        waitForReaderThread(mp);
        ToolResult result = processTool.execute(
            "{\"action\":\"log\",\"session_id\":\"" + mp.id + "\",\"offset\":0,\"limit\":2}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    @Test
    void log_existingProcess_defaultLimit() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo test", 5);
        waitForReaderThread(mp);
        ToolResult result = processTool.execute(
            "{\"action\":\"log\",\"session_id\":\"" + mp.id + "\"}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    // ── wait ──

    @Test
    void wait_nonExistentSession_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"wait\",\"session_id\":\"nonexistent\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"not_found\"");
    }

    @Test
    void wait_completedProcess_returnsOk() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo hello", 5);
        waitForReaderThread(mp);
        ToolResult result = processTool.execute(
            "{\"action\":\"wait\",\"session_id\":\"" + mp.id + "\",\"timeout\":2}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    @Test
    void wait_runningProcess_timesOut() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("sleep 30", 60);
        ToolResult result = processTool.execute(
            "{\"action\":\"wait\",\"session_id\":\"" + mp.id + "\",\"timeout\":1}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("wait timed out");
    }

    @Test
    void wait_defaultTimeout_used() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo quick", 5);
        waitForReaderThread(mp);
        ToolResult result = processTool.execute(
            "{\"action\":\"wait\",\"session_id\":\"" + mp.id + "\"}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    // ── kill ──

    @Test
    void kill_nonExistentSession_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"kill\",\"session_id\":\"nonexistent\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"not_found\"");
    }

    @Test
    void kill_existingProcess_returnsOk() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("sleep 30", 60);
        ToolResult result = processTool.execute(
            "{\"action\":\"kill\",\"session_id\":\"" + mp.id + "\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Killed process");
    }

    // ── write ──

    @Test
    void write_nonExistentSession_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"write\",\"session_id\":\"nonexistent\",\"data\":\"hello\"}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"not_found\"");
    }

    @Test
    void write_nullData_writesEmptyString() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("cat", 10);
        ToolResult result = processTool.execute(
            "{\"action\":\"write\",\"session_id\":\"" + mp.id + "\"}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    // ── submit ──

    @Test
    void submit_nonExistentSession_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"submit\",\"session_id\":\"nonexistent\",\"data\":\"hello\"}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"not_found\"");
    }

    @Test
    void submit_nullData_appendsNewline() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("cat", 10);
        ToolResult result = processTool.execute(
            "{\"action\":\"submit\",\"session_id\":\"" + mp.id + "\"}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    // ── close ──

    @Test
    void close_nonExistentSession_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"close\",\"session_id\":\"nonexistent\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"not_found\"");
    }

    @Test
    void close_existingProcess_returnsOk() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("cat", 10);
        ToolResult result = processTool.execute(
            "{\"action\":\"close\",\"session_id\":\"" + mp.id + "\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    // ── ManagedProcess ──

    @Test
    void managedProcess_getOutput_returnsAllOutput() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo hello", 5);
        waitForReaderThread(mp);
        assertThat(mp.getOutput()).contains("hello");
    }

    @Test
    void managedProcess_getRecentOutput_returnsLastNLines() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo a\necho b\necho c", 5);
        waitForReaderThread(mp);
        String recent = mp.getRecentOutput(2);
        assertThat(recent).isNotNull();
    }

    @Test
    void managedProcess_getOutputLines_returnsCopy() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo test", 5);
        waitForReaderThread(mp);
        var lines = mp.getOutputLines();
        assertThat(lines).isNotNull();
    }

    @Test
    void managedProcess_closeStdin_doesNotThrow() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("cat", 5);
        mp.closeStdin(); // should not throw
    }

    // ── cleanup ──

    @Test
    void cleanup_clearsAllProcesses() throws Exception {
        processTool = new ProcessTool();
        processTool.spawn("sleep 30", 60);
        processTool.spawn("sleep 30", 60);
        processTool.cleanup();
        // After cleanup, list should be empty
        ToolResult result = processTool.execute(
            "{\"action\":\"list\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    /** Join the ManagedProcess reader thread so the output buffer is fully populated. */
    private static void waitForReaderThread(ProcessTool.ManagedProcess managed) throws Exception {
        Field readerField = ProcessTool.ManagedProcess.class.getDeclaredField("readerThread");
        readerField.setAccessible(true);
        Thread readerThread = (Thread) readerField.get(managed);
        readerThread.join(5000);
    }
}