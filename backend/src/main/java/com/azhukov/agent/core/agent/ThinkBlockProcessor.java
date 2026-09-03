package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Think-block (reasoning scratchpad) utilities — ported from Hermes
 * {@code strip_think_blocks} (agent_runtime_helpers.py).
 * <p>
 * Extracted from DefaultAgentRuntime to reduce its size. All methods are
 * static and have no dependencies on instance state.
 */
public final class ThinkBlockProcessor {

    private ThinkBlockProcessor() {}

    // Precompiled patterns for tool-call XML blocks (Hermes parity)
    // Tag names mirror Hermes _TOOL_CALL_TAG_NAMES
    private static final String[] TOOL_CALL_TAGS = {
        "tool_call", "tool_calls", "tool_result", "function_call", "function_calls"
    };

    private static final Pattern[] TOOL_CALL_BLOCK_PATTERNS;
    static {
        TOOL_CALL_BLOCK_PATTERNS = new Pattern[TOOL_CALL_TAGS.length];
        for (int i = 0; i < TOOL_CALL_TAGS.length; i++) {
            TOOL_CALL_BLOCK_PATTERNS[i] = Pattern.compile(
                "<" + TOOL_CALL_TAGS[i] + "\\b[^>]*>.*?</" + TOOL_CALL_TAGS[i] + ">",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
        }
    }

    // Named <function name="..."> blocks — boundary-gated so prose mentions
    // like "Use <function> in JavaScript" are preserved.
    // Matches Hermes _NAMED_FUNCTION_BLOCK_PATTERN.
    private static final Pattern NAMED_FUNCTION_BLOCK_PATTERN = Pattern.compile(
        "(?:^|\\n|(?<=[.!?:]))[ \\t]*<function\\b[^>]*\\bname\\s*=[^>]*>(?:(?:(?!</function>).)*)</function>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Stray orphan tool-call/function closers
    private static final Pattern STRAY_TOOL_CALL_CLOSER_PATTERN = Pattern.compile(
        "</(?:tool_call|tool_calls|tool_result|function_call|function_calls|function)>\\s*",
        Pattern.CASE_INSENSITIVE
    );

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
            || lower.contains("\u21cb");
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
     * Also strips standalone tool-call XML blocks that some open models
     * (Gemma variants on OpenRouter) emit inside assistant content.
     */
    public static String stripThinkBlocksFromString(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String result = content;

        // 1. Closed tag pairs (case-insensitive, non-greedy)
        result = result.replaceAll("(?is)\u21cb.*?\u21cb", "");
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

        // 4. Standalone tool-call XML blocks (Hermes parity: strip_think_blocks)
        //    Some open models emit these inside assistant content instead of
        //    via the structured tool_calls field.
        for (Pattern p : TOOL_CALL_BLOCK_PATTERNS) {
            result = p.matcher(result).replaceAll("");
        }

        // 4b. Named <function name="..."> blocks — boundary-gated
        result = NAMED_FUNCTION_BLOCK_PATTERN.matcher(result).replaceAll("");

        // 4c. Stray orphan tool-call/function closers
        result = STRAY_TOOL_CALL_CLOSER_PATTERN.matcher(result).replaceAll("");

        return result;
    }

    /**
     * Check if content has actual visible text after stripping think blocks.
     */
    public static boolean hasContentAfterThinkBlock(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String stripped = stripThinkBlocksFromString(content).trim();
        return !stripped.isEmpty();
    }

    /**
     * Extract visible content from a chat response after stripping think blocks.
     */
    public static String extractVisibleContent(ChatResponse response) {
        if (response == null || response.content() == null) {
            return "";
        }
        return stripThinkBlocksFromString(response.content()).trim();
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