package com.azhukov.agent.core.budget;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DefaultIterationBudget implements IterationBudget {

    private final AgentProperties.BudgetProperties config;
    private final Map<UUID, AtomicInteger> sessionTurnCounters = new ConcurrentHashMap<>();

    public DefaultIterationBudget(AgentProperties properties) {
        this.config = properties.getBudget();
    }

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
        if (!config.isEnabled()) {
            return snapshot;
        }
        if (snapshot.exhausted()) {
            return snapshot;
        }
        boolean exhausted = isExhausted(new TurnSnapshot(
            snapshot.sessionId(),
            snapshot.startedAt(),
            snapshot.turnIndex(),
            snapshot.modelCalls() + 1,
            snapshot.toolExecutions(),
            snapshot.totalInputTokens() + Math.max(0, inputTokens),
            snapshot.totalOutputTokens() + Math.max(0, outputTokens),
            snapshot.totalToolDurationMs(),
            false,
            null
        ));
        return new TurnSnapshot(
            snapshot.sessionId(),
            snapshot.startedAt(),
            snapshot.turnIndex(),
            snapshot.modelCalls() + 1,
            snapshot.toolExecutions(),
            snapshot.totalInputTokens() + Math.max(0, inputTokens),
            snapshot.totalOutputTokens() + Math.max(0, outputTokens),
            snapshot.totalToolDurationMs(),
            exhausted,
            exhausted ? "model call budget exhausted" : null
        );
    }

    @Override
    public TurnSnapshot recordToolExecution(TurnSnapshot snapshot, String toolName, long durationMs) {
        if (!config.isEnabled()) {
            return snapshot;
        }
        if (snapshot.exhausted()) {
            return snapshot;
        }
        boolean exhausted = isExhausted(new TurnSnapshot(
            snapshot.sessionId(),
            snapshot.startedAt(),
            snapshot.turnIndex(),
            snapshot.modelCalls(),
            snapshot.toolExecutions() + 1,
            snapshot.totalInputTokens(),
            snapshot.totalOutputTokens(),
            snapshot.totalToolDurationMs() + Math.max(0, durationMs),
            false,
            null
        ));
        return new TurnSnapshot(
            snapshot.sessionId(),
            snapshot.startedAt(),
            snapshot.turnIndex(),
            snapshot.modelCalls(),
            snapshot.toolExecutions() + 1,
            snapshot.totalInputTokens(),
            snapshot.totalOutputTokens(),
            snapshot.totalToolDurationMs() + Math.max(0, durationMs),
            exhausted,
            exhausted ? "tool execution budget exhausted" : null
        );
    }

    @Override
    public boolean isExhausted(TurnSnapshot snapshot) {
        if (snapshot.exhausted()) {
            return true;
        }
        if (!config.isEnabled()) {
            return false;
        }
        return snapshot.modelCalls() >= config.getMaxModelCallsPerTurn()
            || snapshot.toolExecutions() >= config.getMaxToolExecutionsPerTurn()
            || (snapshot.totalInputTokens() + snapshot.totalOutputTokens()) >= config.getMaxTokensPerTurn()
            || snapshot.totalToolDurationMs() >= config.getMaxToolDurationMsPerTurn();
    }

    @Override
    public BudgetStatus status(TurnSnapshot snapshot) {
        if (snapshot.exhausted()) {
            return new BudgetStatus(false, 0, 0, 0, snapshot.reason());
        }
        int remainingModelCalls = Math.max(0, config.getMaxModelCallsPerTurn() - snapshot.modelCalls());
        int remainingToolExecutions = Math.max(0, config.getMaxToolExecutionsPerTurn() - snapshot.toolExecutions());
        int remainingTokens = Math.max(0, config.getMaxTokensPerTurn() - snapshot.totalInputTokens() - snapshot.totalOutputTokens());
        String reason = null;
        if (remainingModelCalls == 0) reason = "max model calls reached";
        else if (remainingToolExecutions == 0) reason = "max tool executions reached";
        else if (remainingTokens == 0) reason = "max tokens reached";
        return new BudgetStatus(reason == null, remainingModelCalls, remainingToolExecutions, remainingTokens, reason);
    }
}
