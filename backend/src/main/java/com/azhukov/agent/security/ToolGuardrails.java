package com.azhukov.agent.security;

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
     * Returns true if loop detection has halted further tool calls for a specific session.
     *
     * @param sessionId the session to check
     * @return true if halted for this session, false otherwise
     */
    default boolean isHalted(java.util.UUID sessionId) {
        return isHalted();
    }

    /**
     * Resets all stateful tracking between turns.
     */
    default void reset() {
        // no-op by default for backward compatibility
    }

    /**
     * Resets all stateful tracking for a specific session.
     *
     * @param sessionId the session to reset
     */
    default void reset(java.util.UUID sessionId) {
        reset();
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

    /**
     * Returns true if the given tool is classified as mutating (destructive).
     * Mutating tools may make progress across repeated calls with similar args.
     *
     * @param toolName the tool name to check
     * @return true if the tool is classified as mutating, false otherwise
     */
    default boolean isMutating(String toolName) {
        return false;
    }

    /**
     * Returns true if the given tool is classified as idempotent.
     * Idempotent tools called with the same args repeatedly are definitely in a loop.
     *
     * @param toolName the tool name to check
     * @return true if the tool is classified as idempotent, false otherwise
     */
    default boolean isIdempotent(String toolName) {
        return false;
    }
}