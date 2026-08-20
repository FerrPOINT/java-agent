package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hermes parity tests: uniquify_tool_call_ids (message_sanitization.py:551,
 * #58327 loss class). Models reusing one call id in a batch lose the later
 * call's result; strict providers reject duplicate ids outright.
 */
class ToolCallIdUniquifyTest {

    private List<ToolCall> list(ToolCall... calls) {
        return new ArrayList<>(List.of(calls));
    }

    @Test
    void firstKeepsIdLaterGetsSuffix() {
        List<ToolCall> calls = list(
            new ToolCall("call_a", "read_file", "{}"),
            new ToolCall("call_a", "list_dir", "{}"));
        int rewritten = ToolCallValidator.uniquifyToolCallIds(calls);
        assertEquals(1, rewritten);
        assertEquals("call_a", calls.get(0).id());
        assertEquals("call_a_d2", calls.get(1).id());
        // name and arguments preserved
        assertEquals("list_dir", calls.get(1).name());
    }

    @Test
    void tripleDuplicateEscalatesSuffix() {
        List<ToolCall> calls = list(
            new ToolCall("x", "t1", "{}"),
            new ToolCall("x", "t2", "{}"),
            new ToolCall("x", "t3", "{}"));
        ToolCallValidator.uniquifyToolCallIds(calls);
        assertEquals("x", calls.get(0).id());
        assertEquals("x_d2", calls.get(1).id());
        assertEquals("x_d3", calls.get(2).id());
    }

    @Test
    void compositeIdItemHalfSurvives() {
        // Responses-style composite: "call_x|fc_y" collides on the call half only
        List<ToolCall> calls = list(
            new ToolCall("call_x|fc_1", "t1", "{}"),
            new ToolCall("call_x|fc_2", "t2", "{}"));
        ToolCallValidator.uniquifyToolCallIds(calls);
        assertEquals("call_x|fc_1", calls.get(0).id());
        assertEquals("call_x_d2|fc_2", calls.get(1).id());
    }

    @Test
    void distinctIdsUntouched() {
        List<ToolCall> calls = list(
            new ToolCall("a", "t1", "{}"),
            new ToolCall("b", "t2", "{}"));
        assertEquals(0, ToolCallValidator.uniquifyToolCallIds(calls));
        assertEquals("a", calls.get(0).id());
        assertEquals("b", calls.get(1).id());
    }

    @Test
    void blankIdsLeftForFallback() {
        List<ToolCall> calls = list(
            new ToolCall("", "t1", "{}"),
            new ToolCall("", "t2", "{}"));
        assertEquals(0, ToolCallValidator.uniquifyToolCallIds(calls));
        // deterministic fallback path owns blank ids — never rewritten here
    }

    @Test
    void deterministicNoRandomUuids() {
        // Same input → same output (prompt-cache prefix stability)
        List<ToolCall> first = list(
            new ToolCall("dup", "t1", "{}"),
            new ToolCall("dup", "t2", "{}"));
        List<ToolCall> second = list(
            new ToolCall("dup", "t1", "{}"),
            new ToolCall("dup", "t2", "{}"));
        ToolCallValidator.uniquifyToolCallIds(first);
        ToolCallValidator.uniquifyToolCallIds(second);
        assertEquals(first.get(1).id(), second.get(1).id());
        assertFalse(first.get(1).id().matches(".*[0-9a-f]{8}-.*"));
    }

    @Test
    void nullAndSingletonSafe() {
        assertEquals(0, ToolCallValidator.uniquifyToolCallIds(null));
        assertEquals(0, ToolCallValidator.uniquifyToolCallIds(list(new ToolCall("only", "t", "{}"))));
    }

    @Test
    void suffixCollisionWithExistingIdAvoided() {
        // batch already contains "dup_d2" as a REAL id — the rename must skip past it
        List<ToolCall> calls = list(
            new ToolCall("dup", "t1", "{}"),
            new ToolCall("dup_d2", "t2", "{}"),
            new ToolCall("dup", "t3", "{}"));
        ToolCallValidator.uniquifyToolCallIds(calls);
        assertEquals("dup", calls.get(0).id());
        assertEquals("dup_d2", calls.get(1).id()); // real id untouched
        assertEquals("dup_d3", calls.get(2).id()); // skips the taken _d2
    }
}
