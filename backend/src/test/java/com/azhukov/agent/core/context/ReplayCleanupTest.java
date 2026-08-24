package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReplayCleanup} — Hermes parity (replay_cleanup.py).
 */
@DisplayName("ReplayCleanup — replay-history sanitization")
class ReplayCleanupTest {

    // ── isInterruptedToolResult ─────────────────────────────────────────

    @Test
    @DisplayName("detects [command interrupted] marker")
    void detectsCommandInterrupted() {
        assertTrue(ReplayCleanup.isInterruptedToolResult(
            "some output\n[command interrupted]"));
    }

    @Test
    @DisplayName("detects exit_code 130 with interrupt keyword")
    void detectsExitCode130() {
        assertTrue(ReplayCleanup.isInterruptedToolResult(
            "{\"exit_code\": 130, \"error\": \"interrupted by user\"}"));
    }

    @Test
    @DisplayName("detects exit_code -1 with interrupt keyword")
    void detectsExitCodeMinusOne() {
        assertTrue(ReplayCleanup.isInterruptedToolResult(
            "exit_code: -1, process interrupt"));
    }

    @Test
    @DisplayName("does not match exit_code 130 without interrupt keyword")
    void noFalsePositiveExitCode() {
        assertFalse(ReplayCleanup.isInterruptedToolResult(
            "{\"exit_code\": 130, \"output\": \"done\"}"));
    }

    @Test
    @DisplayName("null/empty content is not interrupted")
    void nullIsNotInterrupted() {
        assertFalse(ReplayCleanup.isInterruptedToolResult(null));
        assertFalse(ReplayCleanup.isInterruptedToolResult(""));
    }

    // ── stripInterruptedToolTails ──────────────────────────────────────

    @Test
    @DisplayName("strips interrupted read-only assistant→tool block entirely")
    void stripsInterruptedReadOnlyBlock() {
        ToolCall readCall = new ToolCall("call-1", "read_file", "{}");
        Message assistant = Message.assistantWithToolCalls("reading file", List.of(readCall), 1);
        Message toolResult = Message.toolResult("call-1", "[command interrupted]", 1);
        Message userMsg = Message.user("next message");

        List<Message> input = List.of(assistant, toolResult, userMsg);
        List<Message> result = ReplayCleanup.stripInterruptedToolTails(input);

        // The entire assistant+tool block is removed; user message survives
        assertEquals(1, result.size());
        assertEquals(Role.USER, result.get(0).role());
        assertEquals("next message", result.get(0).content());
    }

    @Test
    @DisplayName("replaces interrupted side-effecting tool with UNKNOWN stub")
    void replacesInterruptedSideEffectingTool() {
        ToolCall writeCall = new ToolCall("call-1", "write_file", "{\"path\":\"test.txt\"}");
        Message assistant = Message.assistantWithToolCalls("writing file", List.of(writeCall), 1);
        Message toolResult = Message.toolResult("call-1", "[command interrupted]", 1);

        List<Message> input = List.of(assistant, toolResult);
        List<Message> result = ReplayCleanup.stripInterruptedToolTails(input);

        // Assistant is kept; tool result replaced with stub
        assertEquals(2, result.size());
        assertEquals(Role.ASSISTANT, result.get(0).role());
        assertEquals(Role.TOOL, result.get(1).role());
        assertTrue(result.get(1).content().contains("UNKNOWN"));
        assertTrue(result.get(1).content().contains("Inspect state"));
    }

    @Test
    @DisplayName("preserves non-interrupted assistant→tool blocks")
    void preservesNonInterruptedBlocks() {
        ToolCall readCall = new ToolCall("call-1", "read_file", "{}");
        Message assistant = Message.assistantWithToolCalls("reading", List.of(readCall), 1);
        Message toolResult = Message.toolResult("call-1", "file content here", 1);
        Message userMsg = Message.user("thanks");

        List<Message> input = List.of(assistant, toolResult, userMsg);
        List<Message> result = ReplayCleanup.stripInterruptedToolTails(input);

        assertEquals(3, result.size());
        assertEquals("file content here", result.get(1).content());
    }

    @Test
    @DisplayName("strips orphan interrupted tool result (no matching assistant)")
    void stripsOrphanInterruptedToolResult() {
        Message userMsg = Message.user("hello");
        Message orphanTool = Message.toolResult("ghost-id", "[command interrupted]", 1);

        List<Message> input = List.of(userMsg, orphanTool);
        List<Message> result = ReplayCleanup.stripInterruptedToolTails(input);

        assertEquals(1, result.size());
        assertEquals(Role.USER, result.get(0).role());
    }

    // ── stripDanglingToolCallTail ──────────────────────────────────────

    @Test
    @DisplayName("strips dangling read-only assistant(tool_calls) tail")
    void stripsDanglingReadOnlyTail() {
        ToolCall readCall = new ToolCall("call-1", "read_file", "{}");
        Message user = Message.user("read the file");
        Message assistant = Message.assistantWithToolCalls("reading file", List.of(readCall), 1);

        List<Message> input = List.of(user, assistant);
        List<Message> result = ReplayCleanup.stripDanglingToolCallTail(input);

        // The dangling assistant is removed
        assertEquals(1, result.size());
        assertEquals(Role.USER, result.get(0).role());
    }

    @Test
    @DisplayName("inserts UNKNOWN stubs for dangling side-effecting tool calls")
    void insertsStubsForDanglingSideEffecting() {
        ToolCall writeCall = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test\"}");
        Message user = Message.user("write the file");
        Message assistant = Message.assistantWithToolCalls("writing", List.of(writeCall), 1);

        List<Message> input = List.of(user, assistant);
        List<Message> result = ReplayCleanup.stripDanglingToolCallTail(input);

        // User + assistant + stub tool result
        assertEquals(3, result.size());
        assertEquals(Role.TOOL, result.get(2).role());
        assertTrue(result.get(2).content().contains("UNKNOWN"));
        assertEquals("call-1", result.get(2).toolCallId());
    }

    @Test
    @DisplayName("does nothing when last message is not assistant(tool_calls)")
    void noDanglingTail() {
        Message user = Message.user("hello");
        Message assistant = Message.assistant("hi there", 1);

        List<Message> input = List.of(user, assistant);
        List<Message> result = ReplayCleanup.stripDanglingToolCallTail(input);

        assertEquals(input, result);
    }

    @Test
    @DisplayName("does nothing when assistant has matching tool results")
    void noDanglingWhenResultsExist() {
        ToolCall readCall = new ToolCall("call-1", "read_file", "{}");
        Message user = Message.user("read file");
        Message assistant = Message.assistantWithToolCalls("reading", List.of(readCall), 1);
        Message toolResult = Message.toolResult("call-1", "content", 1);

        List<Message> input = List.of(user, assistant, toolResult);
        List<Message> result = ReplayCleanup.stripDanglingToolCallTail(input);

        assertEquals(input, result);
    }

    // ── sanitize (entry point) ─────────────────────────────────────────

    @Test
    @DisplayName("sanitize chains both strippers: interrupted then dangling")
    void sanitizeChainsBoth() {
        // Block 1: interrupted read-only tool (gets stripped)
        ToolCall readCall = new ToolCall("call-1", "read_file", "{}");
        Message assistant1 = Message.assistantWithToolCalls("read", List.of(readCall), 1);
        Message interrupted = Message.toolResult("call-1", "[command interrupted]", 1);

        // Block 2: dangling write_file (gets stub)
        ToolCall writeCall = new ToolCall("call-2", "write_file", "{}");
        Message userMsg = Message.user("now write");
        Message assistant2 = Message.assistantWithToolCalls("writing", List.of(writeCall), 2);

        List<Message> input = List.of(assistant1, interrupted, userMsg, assistant2);
        List<Message> result = ReplayCleanup.sanitize(input);

        // Block 1 stripped, user + assistant2 + stub remain
        assertEquals(3, result.size());
        assertEquals(Role.USER, result.get(0).role());
        assertEquals(Role.ASSISTANT, result.get(1).role());
        assertEquals(Role.TOOL, result.get(2).role());
        assertTrue(result.get(2).content().contains("UNKNOWN"));
    }

    @Test
    @DisplayName("sanitize returns same content when nothing to strip")
    void sanitizeNoop() {
        Message user = Message.user("hello");
        Message assistant = Message.assistant("hi", 1);

        List<Message> input = List.of(user, assistant);
        List<Message> result = ReplayCleanup.sanitize(input);

        assertEquals(input.size(), result.size());
        assertEquals(input, result);
    }

    @Test
    @DisplayName("sanitize handles null/empty")
    void sanitizeNullEmpty() {
        assertNull(ReplayCleanup.sanitize(null));
        assertTrue(ReplayCleanup.sanitize(List.of()).isEmpty());
    }

    // ── Mixed scenarios ────────────────────────────────────────────────

    @Test
    @DisplayName("multiple interrupted blocks all get stripped")
    void multipleInterruptedBlocks() {
        ToolCall read1 = new ToolCall("c1", "read_file", "{}");
        ToolCall read2 = new ToolCall("c2", "search_files", "{}");
        Message a1 = Message.assistantWithToolCalls("read1", List.of(read1), 1);
        Message t1 = Message.toolResult("c1", "[command interrupted]", 1);
        Message a2 = Message.assistantWithToolCalls("search", List.of(read2), 2);
        Message t2 = Message.toolResult("c2", "exit_code: 130, interrupt", 2);
        Message user = Message.user("try again");

        List<Message> input = List.of(a1, t1, a2, t2, user);
        List<Message> result = ReplayCleanup.stripInterruptedToolTails(input);

        assertEquals(1, result.size());
        assertEquals(Role.USER, result.get(0).role());
    }

    @Test
    @DisplayName("partial interrupted results: non-interrupted kept, interrupted replaced")
    void partialInterruptedResults() {
        // Two tool calls: one interrupted (side-effecting), one not
        ToolCall writeCall = new ToolCall("c1", "write_file", "{}");
        ToolCall readCall = new ToolCall("c2", "read_file", "{}");
        Message assistant = Message.assistantWithToolCalls("both", List.of(writeCall, readCall), 1);
        Message writeResult = Message.toolResult("c1", "[command interrupted]", 1);
        Message readResult = Message.toolResult("c2", "file content", 1);

        List<Message> input = List.of(assistant, writeResult, readResult);
        List<Message> result = ReplayCleanup.stripInterruptedToolTails(input);

        assertEquals(3, result.size());
        assertEquals(Role.ASSISTANT, result.get(0).role());
        // Write replaced with stub
        assertEquals(Role.TOOL, result.get(1).role());
        assertTrue(result.get(1).content().contains("UNKNOWN"));
        // Read preserved
        assertEquals("file content", result.get(2).content());
    }
}