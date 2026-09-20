package com.azhukov.agent.core.budget;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class DefaultIterationBudget implements IterationBudget {

    private final AgentProperties properties;
    private final Map<UUID, AtomicInteger> sessionTurnCounters = new ConcurrentHashMap<>();

    @Override
    public TurnSnapshot startTurn(UUID sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId required");
        }
        int turnIndex = sessionTurnCounters.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();
        return new TurnSnapshot(
            sessionId,
            Instant.now(),
            turnIndex,
            0,
            0,
            0,
            0,
            0L,
            false,
            null
        );
    }

    @Override
    public TurnSnapshot recordModelCall(TurnSnapshot snapshot, int inputTokens, int outputTokens) {
        AgentProperties.BudgetProperties config = properties.getBudget();
        if (!config.isEnabled()) {
            return snapshot;
        }
        if (snapshot.exhausted()) {
            return snapshot;
        }
        TurnSnapshot updated = new TurnSnapshot(
            snapshot.sessionId(), snapshot.startedAt(), snapshot.turnIndex(),
            snapshot.modelCalls() + 1, snapshot.toolExecutions(),
            snapshot.totalInputTokens() + Math.max(0, inputTokens),
            snapshot.totalOutputTokens() + Math.max(0, outputTokens),
            snapshot.totalToolDurationMs(), false, null);
        boolean exhausted = isExhausted(updated, config);
        return new TurnSnapshot(
            updated.sessionId(), updated.startedAt(), updated.turnIndex(),
            updated.modelCalls(), updated.toolExecutions(), updated.totalInputTokens(),
            updated.totalOutputTokens(), updated.totalToolDurationMs(), exhausted,
            exhausted ? exhaustionReason(updated, config) : null
        );
    }

    @Override
    public TurnSnapshot recordToolExecution(TurnSnapshot snapshot, String toolName, long durationMs) {
        AgentProperties.BudgetProperties config = properties.getBudget();
        if (!config.isEnabled()) {
            return snapshot;
        }
        if (snapshot.exhausted()) {
            return snapshot;
        }
        TurnSnapshot updated = new TurnSnapshot(
            snapshot.sessionId(), snapshot.startedAt(), snapshot.turnIndex(),
            snapshot.modelCalls(), snapshot.toolExecutions() + 1,
            snapshot.totalInputTokens(), snapshot.totalOutputTokens(),
            snapshot.totalToolDurationMs() + Math.max(0, durationMs), false, null);
        boolean exhausted = isExhausted(updated, config);
        return new TurnSnapshot(
            updated.sessionId(), updated.startedAt(), updated.turnIndex(),
            updated.modelCalls(), updated.toolExecutions(), updated.totalInputTokens(),
            updated.totalOutputTokens(), updated.totalToolDurationMs(), exhausted,
            exhausted ? exhaustionReason(updated, config) : null
        );
    }

    @Override
    public TurnSnapshot refundToolExecution(TurnSnapshot snapshot) {
        if (!properties.getBudget().isEnabled() || snapshot.toolExecutions() <= 0) {
            return snapshot;
        }
        return new TurnSnapshot(
            snapshot.sessionId(),
            snapshot.startedAt(),
            snapshot.turnIndex(),
            snapshot.modelCalls(),
            snapshot.toolExecutions() - 1,
            snapshot.totalInputTokens(),
            snapshot.totalOutputTokens(),
            snapshot.totalToolDurationMs(),
            false,
            null
        );
    }

    @Override
    public boolean isExhausted(TurnSnapshot snapshot) {
        if (snapshot.exhausted()) {
            return true;
        }
        return isExhausted(snapshot, properties.getBudget());
    }

    private boolean isExhausted(TurnSnapshot snapshot, AgentProperties.BudgetProperties config) {
        if (!config.isEnabled()) {
            return false;
        }
        return snapshot.modelCalls() >= config.getMaxModelCallsPerTurn()
            || snapshot.toolExecutions() >= config.getMaxToolExecutionsPerTurn()
            || (snapshot.totalInputTokens() + snapshot.totalOutputTokens()) >= config.getMaxTokensPerTurn()
            || snapshot.totalToolDurationMs() >= config.getMaxToolDurationMsPerTurn();
    }

    private static String exhaustionReason(TurnSnapshot snapshot, AgentProperties.BudgetProperties config) {
        if (snapshot.modelCalls() >= config.getMaxModelCallsPerTurn()) return "max model calls reached";
        if (snapshot.toolExecutions() >= config.getMaxToolExecutionsPerTurn()) return "max tool executions reached";
        if (snapshot.totalInputTokens() + snapshot.totalOutputTokens() >= config.getMaxTokensPerTurn()) return "max tokens reached";
        if (snapshot.totalToolDurationMs() >= config.getMaxToolDurationMsPerTurn()) return "max tool duration reached";
        return "iteration budget exhausted";
    }

    @Override
    public BudgetStatus status(TurnSnapshot snapshot) {
        AgentProperties.BudgetProperties config = properties.getBudget();
        if (snapshot.exhausted()) {
            int remainingModelCalls = Math.max(0, config.getMaxModelCallsPerTurn() - snapshot.modelCalls());
            int remainingToolExecutions = Math.max(0, config.getMaxToolExecutionsPerTurn() - snapshot.toolExecutions());
            int remainingTokens = Math.max(0, config.getMaxTokensPerTurn() - snapshot.totalInputTokens() - snapshot.totalOutputTokens());
            long remainingToolDurationMs = Math.max(0L, config.getMaxToolDurationMsPerTurn() - snapshot.totalToolDurationMs());
            return new BudgetStatus(false, remainingModelCalls, remainingToolExecutions, remainingTokens,
                remainingToolDurationMs, snapshot.reason());
        }
        int remainingModelCalls = Math.max(0, config.getMaxModelCallsPerTurn() - snapshot.modelCalls());
        int remainingToolExecutions = Math.max(0, config.getMaxToolExecutionsPerTurn() - snapshot.toolExecutions());
        int remainingTokens = Math.max(0, config.getMaxTokensPerTurn() - snapshot.totalInputTokens() - snapshot.totalOutputTokens());
        long remainingToolDurationMs = Math.max(0L, config.getMaxToolDurationMsPerTurn() - snapshot.totalToolDurationMs());
        String reason = null;
        if (remainingModelCalls == 0) reason = "max model calls reached";
        else if (remainingToolExecutions == 0) reason = "max tool executions reached";
        else if (remainingTokens == 0) reason = "max tokens reached";
        else if (remainingToolDurationMs == 0) reason = "max tool duration reached";
        return new BudgetStatus(reason == null, remainingModelCalls, remainingToolExecutions, remainingTokens,
            remainingToolDurationMs, reason);
    }
}
