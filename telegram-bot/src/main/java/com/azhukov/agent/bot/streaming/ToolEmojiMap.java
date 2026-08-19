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
        if (args == null || args.isBlank() || args.equals("{}") || args.equals("null")) return null;
        try {
            // Parse JSON args and extract primary argument per tool
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(args);
            
            // Map tool → primary argument field
            String primaryField = switch (toolName) {
                case "terminal", "process" -> "command";
                case "web_search" -> "query";
                case "web_extract" -> "urls";
                case "read_file", "write_file", "patch" -> "path";
                case "search_files" -> "pattern";
                case "browser_navigate", "browser_exec" -> "url";
                case "browser_type" -> "text";
                case "vision_analyze" -> "question";
                case "skill_view", "skill_manage" -> "name";
                case "session_search" -> "query";
                case "delegate_task" -> "goal";
                case "clarify" -> "question";
                case "cronjob" -> "action";
                case "memory" -> "content";
                case "todo" -> "todos";
                default -> null;
            };
            
            if (primaryField != null) {
                com.fasterxml.jackson.databind.JsonNode val = node.get(primaryField);
                if (val != null && !val.isNull()) {
                    String text = val.isTextual() ? val.asText() : val.toString();
                    if (!text.isBlank()) {
                        // Truncate to 40 chars
                        if (text.length() > 40) text = text.substring(0, 37) + "...";
                        // Collapse newlines
                        text = text.replaceAll("\\s+", " ").trim();
                        return "\"" + text + "\"";
                    }
                }
            }
            
            // Fallback: first string value in the JSON
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getValue().isTextual()) {
                    String text = entry.getValue().asText();
                    if (!text.isBlank()) {
                        if (text.length() > 40) text = text.substring(0, 37) + "...";
                        return "\"" + text + "\"";
                    }
                }
            }
        } catch (Exception e) {
            // Not valid JSON — try raw extraction
        }
        return null;
    }
}