package com.azhukov.agent.core.memory;

/**
 * Review prompt templates for self-improvement background review.
 */
public final class ReviewPrompts {

    private ReviewPrompts() {}

    public static final String MEMORY_REVIEW_PROMPT = """
        Review the conversation above and consider saving to memory if appropriate.
        Focus on:
        1. User preferences or corrections that should be remembered
        2. Environment facts (OS, tools, setup details)
        3. Conventions or patterns the user prefers

        Use the memory tool to save any durable facts you discover.
        If nothing worth saving, respond with "Nothing to save."
        Do not save trivial or temporary information.
        """;

    public static final String SKILL_REVIEW_PROMPT = """
        Review the conversation above and consider if a new skill should be created
        or an existing one updated based on the patterns and workflows demonstrated.

        Focus on:
        1. Recurring tasks that could be automated
        2. Common patterns in user requests
        3. Domain-specific knowledge that would help future conversations

        If no skill changes are needed, respond with "No skill changes needed."
        """;

    public static final String COMBINED_REVIEW_PROMPT = """
        Review the conversation above and consider:
        1. Save durable facts to memory (user preferences, environment, corrections)
        2. Create or update skills if recurring patterns are identified

        Use the memory tool for saving facts.
        If nothing to save, respond with "Nothing to save."
        """;
}