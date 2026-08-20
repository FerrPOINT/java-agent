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

    /** Hermes parity: LENGTH continuation nudge (user-language). */
    public static final String LENGTH_NUDGE =
        "Продолжи с того места, где ты остановился. Не начинай заново и не повторяй уже написанный текст. Закончи ответ напрямую.";

    /** Hermes _EMPTY_TOOL_RESPONSE_NUDGE: post-tool-round empty response. */
    public static final String EMPTY_AFTER_TOOLS_NUDGE =
        "Ты выполнил tool calls, но вернул пустой ответ. Обработай результаты инструментов выше и продолжи задачу.";

    /** Empty-response nudge without a preceding tool round. */
    public static final String EMPTY_NUDGE =
        "Пожалуйста, продолжи свой ответ на языке пользователя.";

    /** Hermes _DROPPED_TOOLCALL_NUDGE_CONTENT: narration without the actual call. */
    public static final String DROPPED_TOOLCALL_NUDGE =
        "Your previous turn indicated a tool call but none was included. "
            + "Do not narrate a plan or restate intent — issue the actual tool call now to continue the task.";

    private ResponseRecoveryPolicy() {
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
}
