package com.azhukov.agent.core.state;

import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;

/**
 * Tracks state for a single agent turn: tool calls, failures, results.
 * Used by guardrails and the runtime to detect loops and escalate.
 */
@RequiredArgsConstructor
public class TurnState {

    public record ToolExecution(
        int step,
        String toolName,
        String arguments,
        ToolResult result,
        long durationMs
    ) {}

    private final String sessionId;
    private final int turnIndex;
    private final List<ToolExecution> executions = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> repeatCallCounts = new ConcurrentHashMap<>();
    private int modelCalls = 0;
    private boolean halted = false;
    private String haltReason;


    public void recordModelCall() {
        modelCalls++;
    }

    public void recordExecution(ToolCall call, ToolResult result, long durationMs) {
        executions.add(new ToolExecution(modelCalls, call.name(), call.arguments(), result, durationMs));
        String key = call.name() + ":" + call.arguments();
        repeatCallCounts.merge(key, 1, Integer::sum);
        if (!result.success()) {
            failureCounts.merge(call.name(), 1, Integer::sum);
        }
    }

    public int failureCountFor(String toolName) {
        return failureCounts.getOrDefault(toolName, 0);
    }

    public int repeatCountFor(ToolCall call) {
        return repeatCallCounts.getOrDefault(call.name() + ":" + call.arguments(), 0);
    }

    public boolean isRepeatedFailure(ToolCall call) {
        return repeatCountFor(call) >= 2 && !executions.isEmpty()
            && executions.get(executions.size() - 1).result().success();
    }

    public int totalExecutions() {
        return executions.size();
    }

    public int modelCalls() {
        return modelCalls;
    }

    public List<ToolExecution> executions() {
        return Collections.unmodifiableList(executions);
    }

    public void halt(String reason) {
        this.halted = true;
        this.haltReason = reason;
    }

    public boolean isHalted() {
        return halted;
    }

    public String haltReason() {
        return haltReason;
    }

    public String sessionId() {
        return sessionId;
    }

    public int turnIndex() {
        return turnIndex;
    }
}