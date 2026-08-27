package com.azhukov.agent.core.agent;

/**
 * P-08 (Hermes conversation_loop.py:308 _MAX_OUTER_LOOP_ERRORS, #92450):
 * bounds the number of escaped outer-loop exceptions per user turn. With
 * effectively unlimited iteration budgets, a permanent local failure would
 * otherwise spin forever. Cap = min(8, max(1, maxIterations)).
 */
public final class OuterErrorBudget {

    /** Hermes _MAX_OUTER_LOOP_ERRORS. */
    public static final int MAX_OUTER_LOOP_ERRORS = 8;

    private final int cap;
    private int count = 0;

    public OuterErrorBudget(int maxIterations) {
        int iterationFloor = Math.max(1, maxIterations);
        this.cap = Math.min(MAX_OUTER_LOOP_ERRORS, iterationFloor);
    }

    /** Records one escaped exception; returns true when the turn must stop. */
    public boolean recordAndCheckExhausted() {
        count++;
        return count >= cap;
    }

    public int count() {
        return count;
    }

    public int cap() {
        return cap;
    }

    /** Terminal user-facing message (Hermes "repeated_outer_errors" shape). */
    public String exhaustedMessage(String lastError) {
        return "I apologize, but I encountered repeated errors (" + count + "/" + cap + "): "
            + (lastError == null ? "unknown" : lastError);
    }
}
