package com.azhukov.agent.core.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Hermes agent/empty_response_guard.py — deterministic-empty detection.
 *
 * <p>Tracks the last empty-response attempts (model, provider, finishReason,
 * outputTokens). A streak is "deterministic" when ≥2 consecutive attempts all
 * had usage present with ZERO output tokens and an identical signature — the
 * model is provably billing input tokens while producing nothing, so further
 * paid retries are skipped. Any attempt with missing usage or non-zero output
 * keeps this false (fail open — transients deserve their retries).</p>
 *
 * <p>Turn-scoped instance: reset together with the empty-retry budget at turn
 * start and on fallback-model switch (Hermes resets on both).</p>
 */
public final class EmptyResponseGuard {

    /** Hermes DEFAULT_EMPTY_RETRY_BUDGET. */
    public static final int DEFAULT_EMPTY_RETRY_BUDGET = 3;

    private record Attempt(String model, String provider, String finishReason, Long outputTokens) {}

    private final Deque<Attempt> attempts = new ArrayDeque<>();
    private static final int MAX_TRACKED = 5;

    /** Hermes record_empty_attempt: usage absent → null outputTokens (fail-open marker). */
    public void recordEmptyAttempt(String model, String provider, String finishReason, Long outputTokens) {
        attempts.addLast(new Attempt(model, provider, finishReason, outputTokens));
        while (attempts.size() > MAX_TRACKED) {
            attempts.removeFirst();
        }
    }

    /**
     * Hermes deterministic_empty: ≥2 consecutive attempts, ALL with usage present,
     * zero output tokens, identical (model, provider, finishReason). Fail-open.
     */
    public boolean deterministicEmpty() {
        if (attempts.size() < 2) {
            return false;
        }
        Attempt[] last = attempts.toArray(new Attempt[0]);
        for (int i = last.length - 2; i >= 0; i--) {
            Attempt a = last[i];
            Attempt b = last[i + 1];
            if (a.outputTokens() == null || b.outputTokens() == null) {
                return false; // missing usage → fail open
            }
            if (a.outputTokens() != 0 || b.outputTokens() != 0) {
                return false; // real output somewhere in the streak → transient
            }
            if (!Objects.equals(a.model(), b.model())
                || !Objects.equals(a.provider(), b.provider())
                || !Objects.equals(a.finishReason(), b.finishReason())) {
                return false; // signature changed (e.g. fallback kicked in)
            }
        }
        return true;
    }

    /** Reset on fallback-model switch / new turn. */
    public void reset() {
        attempts.clear();
    }
}
