package com.azhukov.agent.bot.streaming;

import java.util.Map;

/**
 * Tool emoji map — displays an emoji icon for each tool call in the streaming message.
 * Ported from Hermes agent/display.py:get_tool_emoji + tools/registry.py emoji fields.
 */
public final class ToolEmojiMap {

    private ToolEmojiMap() {}

    private static final Map<String, String> EMOJIS = Map.ofEntries(
        Map.entry("read_file", "📄"),
        Map.entry("write_file", "📝"),
        Map.entry("patch", "📝"),
        Map.entry("search_files", "🔎"),
        Map.entry("delete_file", "🗑️"),
        Map.entry("terminal", "🖥️"),
        Map.entry("process", "⚙️"),
        Map.entry("web_search", "🔍"),
        Map.entry("web_extract", "🌐"),
        Map.entry("vision_analyze", "👁️"),
        Map.entry("image_generate", "🎨"),
        Map.entry("text_to_speech", "🔊"),
        Map.entry("memory", "🧠"),
        Map.entry("session_search", "🔎"),
        Map.entry("todo", "📋"),
        Map.entry("clarify", "❓"),
        Map.entry("delegate_task", "🔀"),
        Map.entry("send_message", "📤"),
        Map.entry("skill_view", "📚"),
        Map.entry("skills_list", "📚"),
        Map.entry("skill_manage", "📝"),
        Map.entry("mcp_tool", "🔌"),
        Map.entry("cronjob", "⏰"),
        Map.entry("execute_code", "🐍"),
        Map.entry("browser_navigate", "🌐"),
        Map.entry("browser_click", "👆"),
        Map.entry("browser_type", "⌨️"),
        Map.entry("browser_back", "◀️"),
        Map.entry("browser_forward", "▶️"),
        Map.entry("browser_exec", "🌐"),
        Map.entry("browser_screenshot", "📸"),
        Map.entry("browser_vision", "👁️"),
        Map.entry("browser_wait", "⏳"),
        Map.entry("browser_close", "🔒"),
        Map.entry("browser_scroll", "📜"),
        Map.entry("browser_get_text", "📖")
    );

    private static final String DEFAULT_EMOJI = "⚙️";

    public static String getEmoji(String toolName) {
        if (toolName == null || toolName.isBlank()) return DEFAULT_EMOJI;
        return EMOJIS.getOrDefault(toolName, DEFAULT_EMOJI);
    }

    /**
     * Build a short one-line preview of a tool call for display in the streaming message.
     * Format: "emoji tool_name: \"preview\""
     */
    public static String formatToolCall(String toolName, String args) {
        String emoji = getEmoji(toolName);
        String preview = buildPreview(toolName, args);
        if (preview != null && !preview.isBlank()) {
            return emoji + " " + toolName + ": " + preview;
        }
        return emoji + " " + toolName + "...";
    }

    private static String buildPreview(String toolName, String args) {
        if (args == null || args.isBlank() || args.equals("{}")) return null;
        // Try to extract primary argument for common tools
        try {
            String lower = args.toLowerCase();
            // Extract first string value from JSON args
            int colonIdx = args.indexOf(':');
            if (colonIdx < 0) return null;
            int quoteStart = args.indexOf('"', colonIdx);
            if (quoteStart < 0) return null;
            int quoteEnd = args.indexOf('"', quoteStart + 1);
            if (quoteEnd < 0) return null;
            String value = args.substring(quoteStart + 1, quoteEnd);
            if (value.isBlank()) return null;
            // Truncate to 60 chars
            if (value.length() > 60) {
                value = value.substring(0, 57) + "...";
            }
            return "\"" + value + "\"";
        } catch (Exception e) {
            return null;
        }
    }
}