package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;

import java.util.List;

/**
 * S7: Selects the appropriate review prompt based on conversation content.
 * <p>
 * Ported from Hermes' {@code spawn_background_review_thread} prompt-selection logic.
 * Decides between memory-only, skill-only, or combined review prompts by
 * analyzing the conversation for:
 * <ul>
 *   <li>Skill-related content → skill review</li>
 *   <li>Personal facts/preferences → memory review</li>
 *   <li>Both → combined review</li>
 * </ul>
 */
public final class ReviewPromptSelector {

    private ReviewPromptSelector() {}

    /**
     * Signals indicating skill-related conversation content.
     */
    private static final List<String> SKILL_SIGNALS = List.of(
        "skill", "/skill", "skill_view", "skills_list", "skill_manage",
        "stop doing", "don't format", "don't do", "this is too verbose",
        "just give me the answer", "you always do", "i hate when",
        "workflow", "approach", "technique", "workaround", "debugging",
        "pitfall", "step", "process", "procedure", "pattern"
    );

    /**
     * Signals indicating personal facts/preferences (memory content).
     */
    private static final List<String> MEMORY_SIGNALS = List.of(
        "i like", "i prefer", "i work", "i'm a", "i am a", "i use",
        "i live", "my name", "my job", "my role", "my team", "my project",
        "my company", "my stack", "my setup", "my environment",
        "remember that i", "remember that my", "don't forget that",
        "i speak", "i code in", "i write", "i build", "i manage",
        "i love", "i hate", "i enjoy", "i need", "i want",
        "timezone", "my timezone", "language preference"
    );

    /**
     * Determine which review prompt to use based on conversation content.
     *
     * @param messages the conversation messages
     * @return the appropriate review prompt
     */
    public static String selectPrompt(List<Message> messages) {
        boolean hasSkillSignal = hasSkillSignals(messages);
        boolean hasMemorySignal = hasMemorySignals(messages);

        if (hasSkillSignal && hasMemorySignal) {
            return ReviewPrompts.COMBINED_REVIEW_PROMPT;
        } else if (hasSkillSignal) {
            return ReviewPrompts.SKILL_REVIEW_PROMPT;
        } else if (hasMemorySignal) {
            return ReviewPrompts.MEMORY_REVIEW_PROMPT;
        }
        // Default: combined — don't miss anything
        return ReviewPrompts.COMBINED_REVIEW_PROMPT;
    }

    /**
     * Determine the review type for testing/debugging.
     */
    public static ReviewType selectReviewType(List<Message> messages) {
        boolean hasSkillSignal = hasSkillSignals(messages);
        boolean hasMemorySignal = hasMemorySignals(messages);

        if (hasSkillSignal && hasMemorySignal) {
            return ReviewType.COMBINED;
        } else if (hasSkillSignal) {
            return ReviewType.SKILL_ONLY;
        } else if (hasMemorySignal) {
            return ReviewType.MEMORY_ONLY;
        }
        return ReviewType.COMBINED;
    }

    private static boolean hasSkillSignals(List<Message> messages) {
        if (messages == null) return false;
        for (Message msg : messages) {
            if (msg.content() == null || msg.content().isBlank()) continue;
            String lower = msg.content().toLowerCase();
            for (String signal : SKILL_SIGNALS) {
                if (lower.contains(signal)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasMemorySignals(List<Message> messages) {
        if (messages == null) return false;
        for (Message msg : messages) {
            if (msg.content() == null || msg.content().isBlank()) continue;
            // Only check user messages for personal facts
            if (msg.role() != Role.USER) continue;
            String lower = msg.content().toLowerCase();
            for (String signal : MEMORY_SIGNALS) {
                if (lower.contains(signal)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The type of review selected.
     */
    public enum ReviewType {
        MEMORY_ONLY,
        SKILL_ONLY,
        COMBINED
    }
}