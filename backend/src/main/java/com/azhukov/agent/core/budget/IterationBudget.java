package com.azhukov.agent.core.budget;

import java.time.Instant;
import java.util.UUID;

public interface IterationBudget {

    TurnSnapshot startTurn(UUID sessionId);

    TurnSnapshot recordModelCall(TurnSnapshot snapshot, int inputTokens, int outputTokens);

    TurnSnapshot recordToolExecution(TurnSnapshot snapshot, String toolName, long durationMs);

    boolean isExhausted(TurnSnapshot snapshot);

    BudgetStatus status(TurnSnapshot snapshot);

    record TurnSnapshot(
        UUID sessionId,
        Instant startedAt,
        int turnIndex,
        int modelCalls,
        int toolExecutions,
        int totalInputTokens,
        int totalOutputTokens,
        long totalToolDurationMs,
        boolean exhausted,
        String reason
    ) {
        public TurnSnapshot {
            if (sessionId == null) throw new IllegalArgumentException("sessionId required");
        }
    }

    record BudgetStatus(
        boolean allowed,
        int remainingModelCalls,
        int remainingToolExecutions,
        int remainingTokens,
        String reason
    ) {}
}
