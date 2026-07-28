package com.azhukov.agent.service;

import org.springframework.stereotype.Component;

/**
 * Thread-local holder for capturing token usage from the model client
 * during a single turn execution. The model client writes usage here,
 * and {@link AgentRuntimeService} reads and clears it after the turn.
 */
@Component
public class TurnUsageCollector {

    private final ThreadLocal<int[]> holder = new ThreadLocal<>();

    /** Store prompt and completion token counts from the model response. */
    public void record(int promptTokens, int completionTokens) {
        holder.set(new int[]{promptTokens, completionTokens});
    }

    /** Retrieve and clear the stored usage. Returns null if none recorded. */
    public int[] getAndClear() {
        int[] val = holder.get();
        holder.remove();
        return val;
    }
}