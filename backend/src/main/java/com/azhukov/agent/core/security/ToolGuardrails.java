package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolCall;

public interface ToolGuardrails {

    boolean isToolAllowed(String toolName);

    boolean requiresApproval(ToolCall call);

    /**
     * Records a tool call for loop detection tracking.
     *
     * @param toolName the tool that was called
     * @param args     the arguments passed to the tool (may be null)
     * @param success  whether the tool call succeeded
     */
    default void recordToolCall(String toolName, String args, boolean success) {
        // no-op by default for backward compatibility
    }

    /**
     * Returns true if loop detection has halted further tool calls.
     *
     * @return true if halted, false otherwise
     */
    default boolean isHalted() {
        return false;
    }

    /**
     * Resets all stateful tracking between turns.
     */
    default void reset() {
        // no-op by default for backward compatibility
    }

    /**
     * Returns the set of tools that are explicitly blocked.
     *
     * @return set of blocked tool names (empty by default)
     */
    default java.util.Set<String> getBlockedTools() {
        return java.util.Set.of();
    }

    /**
     * Sets the blocked tools list.
     *
     * @param blockedTools set of tool names to block
     */
    default void setBlockedTools(java.util.Set<String> blockedTools) {
        // no-op by default for backward compatibility
    }
}