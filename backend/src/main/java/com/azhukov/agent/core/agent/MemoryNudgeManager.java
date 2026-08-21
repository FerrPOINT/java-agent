package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.ReviewSummary;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Memory + skill nudge manager — extracted from DefaultAgentRuntime (c1).
 * <p>
 * Tracks per-session counters for memory review and skill creation nudges.
 * Fires background review when thresholds are reached.
 * Ported from Hermes {@code _turns_since_memory} / {@code _iters_since_skill}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryNudgeManager {

    private final AgentProperties properties;
    private final ContextEngine contextEngine;
    private final BackgroundReviewService backgroundReviewService;

    // Per-session nudge counters
    private final ConcurrentHashMap<UUID, AtomicInteger> turnsSinceMemory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicInteger> itersSinceSkill = new ConcurrentHashMap<>();

    /**
     * Initialize the memory turn counter for a session, hydrating from prior
     * conversation history (parity with Hermes M8).
     */
    public void initMemoryCounter(UUID sessionId, long priorUserTurns) {
        int memNudge = properties.getMemory().getNudgeInterval();
        if (memNudge <= 0) return;
        int initial = (int) (priorUserTurns % memNudge);
        log.debug("M8: Hydrated turnsSinceMemory for session {} from history: {} prior user turns, initial={}",
            sessionId, priorUserTurns, initial);
        turnsSinceMemory.computeIfAbsent(sessionId, k -> new AtomicInteger(initial));
    }

    /**
     * Increment the memory turn counter for a session.
     */
    public void incrementMemoryTurns(UUID sessionId) {
        AtomicInteger counter = turnsSinceMemory.get(sessionId);
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    /**
     * Increment the skill iteration counter for a session.
     */
    public void incrementSkillIters(UUID sessionId) {
        itersSinceSkill.computeIfAbsent(sessionId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * Reset the skill iteration counter (called when skill_manage is invoked).
     */
    public void resetSkillIters(UUID sessionId) {
        AtomicInteger counter = itersSinceSkill.get(sessionId);
        if (counter != null) {
            counter.set(0);
        }
    }

    /**
     * Reset the memory turn counter to zero (called when the memory tool is invoked,
     * so the next nudge interval starts fresh after actual memory use).
     */
    public void resetMemoryTurns(UUID sessionId) {
        AtomicInteger counter = turnsSinceMemory.get(sessionId);
        if (counter != null) {
            counter.set(0);
        } else {
            // Ensure the counter exists even if initMemoryCounter was never called
            turnsSinceMemory.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        }
    }

    /**
     * Trigger nudged background review if thresholds are met.
     * Mirrors Hermes {@code _trigger_nudged_background_review}.
     */
    public void triggerNudgedBackgroundReview(Session session, List<Message> turnMessages, boolean interrupted) {
        if (interrupted) return;
        if (backgroundReviewService == null) return;

        // Skip for subagents
        String delegationDepthMeta = session.getMetadata("delegation_depth");
        if (delegationDepthMeta != null) {
            try {
                int depth = Integer.parseInt(delegationDepthMeta.trim());
                if (depth > 0) {
                    log.debug("Skipping background review for subagent (delegationDepth={})", depth);
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        // Hermes parity (turn_finalizer.py:794, cron/scheduler.py:5459): cron/background
        // sessions set skip_background_review — review forks cost ~30K tokens and cron
        // has no human-in-the-loop benefit from a memory/skill review.
        if ("true".equalsIgnoreCase(session.getMetadata("skip_background_review"))) {
            log.debug("Skipping background review for background session (skip_background_review)");
            return;
        }

        int memNudge = properties.getMemory().getNudgeInterval();
        int skillNudge = properties.getSkills().getCreationNudgeInterval();

        boolean shouldReviewMemory = false;
        boolean shouldReviewSkills = false;

        if (memNudge > 0) {
            AtomicInteger turnsCounter = turnsSinceMemory.get(session.id());
            if (turnsCounter != null && turnsCounter.get() >= memNudge) {
                shouldReviewMemory = true;
                turnsCounter.set(0);
            }
        }

        if (skillNudge > 0) {
            AtomicInteger itersCounter = itersSinceSkill.get(session.id());
            if (itersCounter != null && itersCounter.get() >= skillNudge) {
                shouldReviewSkills = true;
                itersCounter.set(0);
            }
        }

        if (!shouldReviewMemory && !shouldReviewSkills) return;

        // Build full conversation history for the review
        List<Message> fullHistory;
        try {
            fullHistory = contextEngine.prepareContext(session, turnMessages);
        } catch (Exception e) {
            log.warn("Failed to prepare full context for background review, using turn messages: {}", e.getMessage());
            fullHistory = turnMessages;
        }

        try {
            backgroundReviewService.clearFlag(session.id());
            backgroundReviewService.reviewTurn(session.id(), fullHistory, session.userId(),
                shouldReviewMemory, shouldReviewSkills);
        } catch (Exception e) {
            log.warn("Background review trigger failed: {}", e.getMessage());
        }

        // Surface any pending review summary from a prior turn
        try {
            String summary = getReviewSummaryForSurface(session.id());
            if (summary != null && !summary.isBlank()) {
                log.info("Background review summary for session {}: {}", session.id(), summary);
            }
        } catch (Exception e) {
            log.debug("No review summary to surface for session {}", session.id());
        }
    }

    /**
     * Check if the background review produced a summary to surface to the user.
     */
    public String getReviewSummaryForSurface(UUID sessionId) {
        if (backgroundReviewService == null) return null;
        if (!backgroundReviewService.hasReviewSummary(sessionId)) return null;
        ReviewSummary summary = backgroundReviewService.getReviewSummary(sessionId);
        if (summary == null || !summary.hasActions()) return null;
        String result = summary.formattedSummary();
        backgroundReviewService.clearFlag(sessionId);
        return result;
    }

    /**
     * Clear nudge state for a session (on session end/reset).
     */
    public void clearSession(UUID sessionId) {
        turnsSinceMemory.remove(sessionId);
        itersSinceSkill.remove(sessionId);
    }
}