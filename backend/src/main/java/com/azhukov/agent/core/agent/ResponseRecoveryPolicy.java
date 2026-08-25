package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.ChatResponse;

/**
 * c2: SHARED response-recovery policy for BOTH runtimes (DefaultAgentRuntime
 * sync loop and AgentStreamingService SSE loop). Single owner of the Hermes
 * parity constants and nudge texts so the two loops can never drift again.
 *
 * <p>Hermes references: LENGTH continuation ceiling 4
 * (conversation_loop.py "length_continue_retries &lt; 4", stitched partial kept
 * on exhaustion 3779-3813), empty-response budget 3
 * (empty_response_guard.DEFAULT_EMPTY_RETRY_BUDGET, jittered backoff 7657-7659),
 * dropped-toolcall 3 consecutive with reset on a landed call (7918-7950).</p>
 */
public final class ResponseRecoveryPolicy {

    /** Hermes parity: LENGTH continuation ceiling is 4 attempts. */
    public static final int MAX_LENGTH_CONTINUATION_ATTEMPTS = 4;
    /** Hermes parity: empty-response budget is 3 (separate counter — never shared). */
    public static final int MAX_EMPTY_RESPONSE_ATTEMPTS = 3;
    /** Hermes _DROPPED_TOOLCALL_RETRIES: 3 consecutive stalls, reset on a successful tool round. */
    public static final int MAX_DROPPED_TOOLCALL_RETRIES = 3;
    /** Hermes truncated_tool_call_retries ceiling (conversation_loop.py:3829): 4 retries with max_tokens boost. */
    public static final int MAX_TRUNCATED_TOOL_CALL_RETRIES = 4;

    /** Hermes _LENGTH_CONTINUATION_OUTPUT_LIMIT (conversation_loop.py:1044): output-length truncation. */
    public static final String LENGTH_NUDGE =
        "[System: Your previous response was truncated by the output "
            + "length limit. Continue exactly where you left off. Do not "
            + "restart or repeat prior text. Finish the answer directly.]";

    /** Hermes _LENGTH_CONTINUATION_NETWORK_STUB (conversation_loop.py:1038): stream cut by network error. */
    public static final String LENGTH_NETWORK_STUB_NUDGE =
        "[System: The previous response was cut off by a "
            + "network error mid-stream. Continue exactly where "
            + "you left off. Do not restart or repeat prior text. "
            + "Finish the answer directly.]";

    /**
     * Hermes _LENGTH_CONTINUATION_DROPPED_TOOLS_PREFIX + interpolated tool list
     * (conversation_loop.py:1056-1071): stream died mid tool-call because the
     * arguments were too large — instructs chunked retry (~8K tokens per call).
     */
    public static String lengthDroppedToolsNudge(java.util.List<String> droppedTools) {
        java.util.List<String> tools = droppedTools == null ? java.util.List.of() : droppedTools;
        String toolList = String.join(", ", tools.subList(0, Math.min(3, tools.size())));
        return "[System: Your previous tool call "
            + "(" + toolList + ") was too large and "
            + "the stream timed out before it "
            + "could be delivered. Do NOT retry "
            + "the same tool call with the same "
            + "large content. Instead, break the "
            + "content into multiple smaller tool "
            + "calls (e.g. use multiple patch calls "
            + "or write smaller files). Each tool "
            + "call's arguments must be under ~8K "
            + "tokens to avoid stream timeouts.]";
    }

    /** Hermes _EMPTY_TOOL_RESPONSE_NUDGE (conversation_loop.py:1116-1120): post-tool-round empty response. */
    public static final String EMPTY_AFTER_TOOLS_NUDGE =
        "You just executed tool calls but returned an "
            + "empty response. Please process the tool "
            + "results above and continue with the task.";

    /** Empty-response nudge without a preceding tool round.
     *  Hermes parity: the plain-empty path uses retry-with-backoff, not a
     *  user-visible nudge text. When a nudge IS needed (e.g. streaming), use
     *  the same English text as the post-tool nudge. The previous Russian text
     *  was a Java-only invention. */
    public static final String EMPTY_NUDGE =
        "Please continue your response in the user's language.";

    /** Hermes _DROPPED_TOOLCALL_NUDGE_CONTENT: narration without the actual call. */
    public static final String DROPPED_TOOLCALL_NUDGE =
        "Your previous turn indicated a tool call but none was included. "
            + "Do not narrate a plan or restate intent — issue the actual tool call now to continue the task.";

    private ResponseRecoveryPolicy() {
    }

    /**
     * Hermes jittered_backoff (empty_response_guard semantics): exponential
     * base * 2^(attempt-1), capped, ±25% jitter (0.75–1.25 multiplier).
     * Canonical implementation — both runtimes delegate here.
     */
    public static long jitteredBackoffMs(int attempt, long baseMs, long capMs) {
        double base = baseMs * Math.pow(2, attempt - 1);
        long capped = (long) Math.min(base, capMs);
        double jitter = 0.75 + java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.5);
        return (long) (capped * jitter);
    }

    /**
     * LENGTH truncation with content and no tool calls, below the ceiling —
     * the response should be continued (stitched), not finalized.
     */
    public static boolean isLengthContinuable(ChatResponse response, int lengthRetries) {
        return "LENGTH".equals(response.finishReason())
            && response.hasContent()
            && !response.hasToolCalls()
            && lengthRetries < MAX_LENGTH_CONTINUATION_ATTEMPTS;
    }

    /**
     * finish_reason signalled tool calls but the parsed array is empty —
     * a dropped tool call; re-prompt while within the consecutive budget.
     */
    public static boolean isDroppedToolcall(ChatResponse response, int droppedRetries) {
        return "TOOL_EXECUTION".equals(response.finishReason())
            && !response.hasToolCalls()
            && droppedRetries < MAX_DROPPED_TOOLCALL_RETRIES;
    }

    /**
     * A landed tool call resets the dropped-toolcall stall budget
     * (Hermes conversation_loop.py:7133).
     */
    public static int resetOnLandedToolCall(int droppedRetries, boolean hasToolCalls) {
        return hasToolCalls ? 0 : droppedRetries;
    }

    /**
     * LENGTH finish_reason WITH tool calls — the model hit the output cap
     * mid-tool-call JSON. The arguments are truncated and must NOT be executed.
     * Hermes parity (conversation_loop.py:3829): {@code _trunc_has_tool_calls}.
     */
    public static boolean isTruncatedToolCall(String finishReason, boolean hasToolCalls) {
        return "LENGTH".equals(finishReason) && hasToolCalls;
    }

    /**
     * Boost max_tokens for a truncated-tool-call retry. Hermes doubles the
     * budget per attempt (conversation_loop.py:3851: {@code _tc_boost_base * (2 ** retries)}),
     * capped at 32768 (or the requested cap if higher).
     *
     * @param baseMaxTokens the current max_tokens setting
     * @param attempt       1-based retry number
     * @return the boosted max_tokens, capped at 32768
     */
    public static int boostedMaxTokens(int baseMaxTokens, int attempt) {
        int base = baseMaxTokens > 0 ? baseMaxTokens : 4096;
        int boosted = base * (int) Math.pow(2, attempt);
        return Math.min(boosted, 32768);
    }
}
