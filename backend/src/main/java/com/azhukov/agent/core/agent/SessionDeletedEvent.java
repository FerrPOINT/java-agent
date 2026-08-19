package com.azhukov.agent.core.agent;

import java.util.UUID;

/**
 * Published when a session is permanently deleted (or rotated away) so that
 * every in-memory component holding per-session state can evict its entries.
 *
 * Without this, per-session ConcurrentHashMap entries in DefaultAgentRuntime,
 * InterruptToken, DefaultContextEngine, PromptCacheTracker, BackgroundReviewService
 * and TurnStateManager accumulate forever (unbounded memory growth).
 */
public record SessionDeletedEvent(UUID sessionId) {}
