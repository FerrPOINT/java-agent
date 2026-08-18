package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;

import java.util.List;

/**
 * Think-block (reasoning scratchpad) utilities — ported from Hermes
 * {@code strip_think_blocks} (agent_runtime_helpers.py).
 * <p>
 * Extracted from DefaultAgentRuntime to reduce its size. All methods are
 * static and have no dependencies on instance state.
 */
public final class ThinkBlockProcessor {

    private ThinkBlockProcessor() {}

    /**
     * Check if content contains any think/reasoning tag.
     */
    public static boolean containsAnyThinkTag(String content) {
        String lower = content.toLowerCase();
        return lower.contains("<antml:thinking>")
            || lower.contains("<thinking>")
            || lower.contains("<reasoning>")
            || lower.contains("<thought>")
            || lower.contains("<reasoning_scratchpad>")
            || lower.contains("⇋");
    }

    /**
     * Strip thinking/reasoning blocks from all messages in the context.
     */
    public static List<Message> stripThinkingBlocks(List<Message> context) {
        return context.stream().map(m -> {
            if (m.content() == null) return m;
            String stripped = stripThinkBlocksFromString(m.content()).trim();
            return new Message(m.role(), stripped, m.toolCall(), m.toolCalls(),
                m.toolCallId(), m.turnIndex(), m.imageCount());
        }).toList();
    }

    /**
     * Strip all thinking/reasoning block variants from a content string.
     * Case-insensitive, matching Hermes strip_think_blocks.
     */
    public static String stripThinkBlocksFromString(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String result = content;

        // 1. Closed tag pairs (case-insensitive, non-greedy)
        result = result.replaceAll("(?is)⇋.*?⇋", "");
        result = result.replaceAll("(?is)<antml:thinking>.*?</antml:thinking>", "");
        result = result.replaceAll("(?is)<thinking>.*?</thinking>", "");
        result = result.replaceAll("(?is)<reasoning>.*?</reasoning>", "");
        result = result.replaceAll("(?is)<thought>.*?</thought>", "");
        result = result.replaceAll("(?is)<REASONING_SCRATCHPAD>.*?</REASONING_SCRATCHPAD>", "");

        // 2. Unterminated reasoning block — open tag at a block boundary
        result = result.replaceAll(
            "(?is)(?:^|\\n)[ \\t]*<(?:think|thinking|reasoning|thought|REASONING_SCRATCHPAD)\\b[^>]*>.*$",
            "");

        // 3. Stray orphan open/close tags
        result = result.replaceAll(
            "(?is)</?(?:think|thinking|reasoning|thought|REASONING_SCRATCHPAD)>\\s*",
            "");
        result = result.replaceAll("(?is)</?antml:thinking>\\s*", "");

        return result;
    }

    /**
     * Check if content has actual visible text after stripping think blocks.
     */
    public static boolean hasContentAfterThinkBlock(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String cleaned = stripThinkBlocksFromString(content);
        return !cleaned.strip().isEmpty();
    }

    /**
     * Check if content contains an incomplete REASONING_SCRATCHPAD (opened but never closed).
     */
    public static boolean hasIncompleteScratchpad(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        return content.contains("<REASONING_SCRATCHPAD>")
            && !content.contains("</REASONING_SCRATCHPAD>");
    }

    /**
     * Detect thinking-budget exhaustion: the model spent ALL output tokens on
     * reasoning and had none left for the actual response.
     */
    public static boolean isThinkingBudgetExhausted(ChatResponse response) {
        if (response == null) {
            return false;
        }
        if (response.hasToolCalls()) {
            return false;
        }
        String content = response.content();
        if (content == null) {
            return true;
        }
        return !hasContentAfterThinkBlock(content) && containsAnyThinkTag(content);
    }
}