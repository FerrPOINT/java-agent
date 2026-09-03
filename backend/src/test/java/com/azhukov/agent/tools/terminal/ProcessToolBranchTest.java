package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for {@link ProcessTool}.
 * Covers all action types, error paths, edge cases.
 */
class ProcessToolBranchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Test
    void list_includesBackgroundProcessMetadataLikeHermes(@TempDir Path dir) throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn(
            "echo ready", 5, false, ignored -> { }, dir.toString(),
            null, null, java.util.List.of("ready"));
        mp.process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        mp.awaitOutputDrain(1000);

        ToolResult result = processTool.execute(
            "{\"action\":\"list\"}", null, Session.create("u", "noop", ""));

        assertThat(result.success()).isTrue();
        JsonNode entry = MAPPER.readTree(result.content()).path("processes").get(0);
        assertThat(entry.path("session_id").asText()).isEqualTo(mp.id);
        assertThat(entry.path("cwd").asText()).isEqualTo(dir.toAbsolutePath().normalize().toString());
        assertThat(entry.path("notify_on_complete").asBoolean()).isTrue();
        assertThat(entry.path("watch_patterns").get(0).asText()).isEqualTo("ready");
        assertThat(entry.path("watch_hit").asBoolean()).isFalse();
    }

    // ── poll ──

    @Test
    void poll_nonExistentSession_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"poll\",\"session_id\":\"nonexistent\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"not_found\"");
        assertThat(result.content()).contains("No process with ID nonexistent");
    }

    @Test
    void poll_missingSessionId_returnsActionableError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"poll\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("session_id is required for poll");
    }

    @Test
    void poll_existingProcess_returnsStatus() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo hello", 5);
        // Wait a bit for output
        Thread.sleep(500);
        ToolResult result = processTool.execute(
            "{\"action\":\"poll\",\"session_id\":\"" + mp.id + "\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"session_id\":\"");
    }

    // ── log ──

    @Test
    void log_nonExistentSession_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"log\",\"session_id\":\"nonexistent\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"not_found\"");
        assertThat(result.content()).contains("No process with ID nonexistent");
    }

    @Test
    void log_existingProcess_withOffsetAndLimit() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo line1\necho line2\necho line3", 5);
        Thread.sleep(500);
        ToolResult result = processTool.execute(
            "{\"action\":\"log\",\"session_id\":\"" + mp.id + "\",\"offset\":0,\"limit\":2}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    @Test
    void log_existingProcess_defaultLimit() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo test", 5);
        Thread.sleep(500);
        ToolResult result = processTool.execute(
            "{\"action\":\"log\",\"session_id\":\"" + mp.id + "\"}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
    }

    @Test
    void log_withoutOffset_returnsTailByDefault() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("seq 1 250", 5);
        mp.process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        mp.awaitOutputDrain(1000);
        ToolResult result = processTool.execute(
            "{\"action\":\"log\",\"session_id\":\"" + mp.id + "\"}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"total_lines\":250");
        assertThat(result.content()).contains("\"showing\":\"200 lines\"");
        assertThat(result.content()).doesNotContain("\"output\":\"1\\n2\\n3");
        assertThat(result.content()).contains("250");
    }

    // ── wait ──

    @Test
    void wait_nonExistentSession_returnsError() {
        processTool = new ProcessTool();
        ToolResult result = processTool.execute(
            "{\"action\":\"wait\",\"session_id\":\"nonexistent\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"not_found\"");
        assertThat(result.content()).contains("No process with ID nonexistent");
    }

    @Test
    void wait_completedProcess_returnsOk() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo hello", 5);
        Thread.sleep(500);
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
        assertThat(result.content()).contains("\"status\":\"timeout\"");
        assertThat(result.content()).contains("\"process_running\":true");
    }

    @Test
    void wait_explicitZeroTimeout_returnsError() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("sleep 30", 60);
        ToolResult result = processTool.execute(
            "{\"action\":\"wait\",\"session_id\":\"" + mp.id + "\",\"timeout\":0}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timeout must be positive (got 0)");
    }

    @Test
    void wait_negativeTimeout_returnsError() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("sleep 30", 60);
        ToolResult result = processTool.execute(
            "{\"action\":\"wait\",\"session_id\":\"" + mp.id + "\",\"timeout\":-1}",
            null, Session.create("u", "noop", ""));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timeout must be positive (got -1)");
    }

    @Test
    void wait_defaultTimeout_used() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo quick", 5);
        Thread.sleep(500);
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
        assertThat(result.content()).contains("No process with ID nonexistent");
    }

    @Test
    void kill_existingProcess_returnsOk() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("sleep 30", 60);
        ToolResult result = processTool.execute(
            "{\"action\":\"kill\",\"session_id\":\"" + mp.id + "\"}", null, Session.create("u", "noop", ""));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"status\":\"killed\"");
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
        assertThat(result.content()).contains("No process with ID nonexistent");
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
        assertThat(result.content()).contains("No process with ID nonexistent");
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
        assertThat(result.content()).contains("No process with ID nonexistent");
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
        assertThat(mp.process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        mp.awaitOutputDrain(1000);
        assertThat(mp.getOutput()).contains("hello");
    }

    @Test
    void managedProcess_getRecentOutput_returnsLastNLines() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo a\necho b\necho c", 5);
        Thread.sleep(500);
        String recent = mp.getRecentOutput(2);
        assertThat(recent).isNotNull();
    }

    @Test
    void managedProcess_getOutputLines_returnsCopy() throws Exception {
        processTool = new ProcessTool();
        ProcessTool.ManagedProcess mp = processTool.spawn("echo test", 5);
        Thread.sleep(500);
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
}
