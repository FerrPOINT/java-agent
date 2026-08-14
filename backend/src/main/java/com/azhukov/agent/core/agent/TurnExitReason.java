package com.azhukov.agent.core.agent;

/**
 * Describes why a turn ended, used by {@link TurnFinalizer} to surface
 * a user-visible explanation when a turn ends abnormally.
 */
public enum TurnExitReason {

    /** Turn completed normally with a text response and no pending tool calls. */
    COMPLETED,

    /** Model returned an empty response after retries. */
    EMPTY_RESPONSE,

    /** Iteration or token budget was exhausted before the model could finish. */
    BUDGET_EXHAUSTED,

    /** Turn was interrupted by the user or an interrupt token. */
    INTERRUPTED,

    /** Turn ended with a pending tool result (no final assistant text after tool calls). */
    PENDING_TOOL_RESULT,

    /** Model call failed after all retries. */
    MODEL_CALL_FAILED,

    /** Guardrails halted the turn. */
    GUARDRAIL_HALTED,

    /** Max turns reached without a completing response. */
    MAX_TURNS_REACHED,

    /** Incomplete &lt;REASONING_SCRATCHPAD&gt; after 2 retries — model ran out of tokens mid-reasoning. */
    INCOMPLETE_SCRATCHPAD,

    /** Model spent all output tokens on reasoning with none left for the response. */
    THINKING_BUDGET_EXHAUSTED,

    /** Empty response after exhausting all recovery retries. */
    EMPTY_RESPONSE_EXHAUSTED,

    /** Content policy error — provider safety filter rejected the prompt (terminal, no retry). */
    CONTENT_POLICY,

    /** All models in the fallback chain failed — primary and every fallback. */
    FALLBACK_EXHAUSTED,

    /** Turn ended for an unknown / unexpected reason. */
    UNKNOWN;

    /**
     * Whether this reason represents an abnormal termination that warrants
     * a user-visible explanation.
     */
    public boolean isAbnormal() {
        return this != COMPLETED;
    }

    /**
     * Human-readable explanation text for the user, or {@code null} if the
     * turn completed normally and no explanation is needed.
     */
    public String explanation() {
        return switch (this) {
            case COMPLETED -> null;
            case EMPTY_RESPONSE -> "[Turn ended: empty response from model]";
            case BUDGET_EXHAUSTED -> "[Turn ended: iteration budget exhausted]";
            case INTERRUPTED -> "[Turn ended: interrupted by user]";
            case PENDING_TOOL_RESULT -> "[Turn ended: pending tool result — agent stopped mid-work]";
            case MODEL_CALL_FAILED -> "[Turn ended: model call failed]";
            case GUARDRAIL_HALTED -> "[Turn ended: halted by guardrails]";
            case MAX_TURNS_REACHED -> "[Turn ended: maximum turns reached without completion]";
            case INCOMPLETE_SCRATCHPAD -> "[Turn ended: incomplete reasoning scratchpad after retries]";
            case THINKING_BUDGET_EXHAUSTED -> "[Turn ended: thinking budget exhausted — all output tokens spent on reasoning]";
            case EMPTY_RESPONSE_EXHAUSTED -> "[Turn ended: empty response from model after exhausting all recovery retries]";
            case CONTENT_POLICY -> "[Turn ended: content policy — provider safety filter rejected the request]";
            case FALLBACK_EXHAUSTED -> "[Turn ended: all models in the fallback chain failed]";
            case UNKNOWN -> "[Turn ended: unknown reason]";
        };
    }
}