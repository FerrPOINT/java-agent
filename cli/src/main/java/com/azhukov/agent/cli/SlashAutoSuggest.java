package com.azhukov.agent.cli;

import org.jline.reader.impl.BufferImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inline auto-suggest for slash commands.
 * <p>
 * When the user types "/", suggests the first matching command name
 * as a grayed-out inline suggestion (similar to fish shell).
 * <p>
 * P1-8: Enhanced to suggest subcommands (e.g. /memory → /memory pending,
 * /memory approve) and suggest from input history.
 */
public class SlashAutoSuggest {

    private final SlashCommandRegistry registry;
    private final List<String> inputHistory = new ArrayList<>();

    // P1-8: Subcommand map — command → list of subcommands
    private static final Map<String, String[]> SUBCOMMANDS = new LinkedHashMap<>();

    static {
        SUBCOMMANDS.put("memory", new String[]{"all", "pending", "approve", "reject", "delete"});
        SUBCOMMANDS.put("tools", new String[]{"list", "disable", "enable"});
        SUBCOMMANDS.put("voice", new String[]{"on", "off", "tts", "status"});
        SUBCOMMANDS.put("busy", new String[]{"queue", "steer", "interrupt", "status"});
        SUBCOMMANDS.put("reasoning", new String[]{"none", "minimal", "low", "medium", "high", "xhigh", "cycle"});
        SUBCOMMANDS.put("model", new String[]{"gpt-4o", "gpt-4o-mini", "claude-3-opus", "claude-3-sonnet"});
        SUBCOMMANDS.put("cron", new String[]{"create", "pause", "resume", "delete"});
        SUBCOMMANDS.put("approvals", new String[]{"list", "approve", "deny"});
        SUBCOMMANDS.put("goal", new String[]{"set", "clear", "show"});
        SUBCOMMANDS.put("snapshot", new String[]{"list", "create", "restore"});
        SUBCOMMANDS.put("plugins", new String[]{"list", "install", "uninstall"});
    }

    public SlashAutoSuggest(SlashCommandRegistry registry) {
        this.registry = registry;
    }

    /**
     * Add an input to the history for future suggestions.
     *
     * @param input the user's input to remember
     */
    public void addToHistory(String input) {
        if (input == null || input.isBlank()) return;
        // Avoid duplicates
        if (!inputHistory.isEmpty() && inputHistory.get(inputHistory.size() - 1).equals(input)) {
            return;
        }
        inputHistory.add(input);
        // Keep last 100 entries
        while (inputHistory.size() > 100) {
            inputHistory.remove(0);
        }
    }

    /**
     * Get the best inline suggestion for the current input buffer.
     *
     * @param buffer the current input text
     * @return the suggested completion (without the typed part), or null
     */
    public String suggest(String buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }

        // P1-8: Suggest from history (for non-slash commands)
        if (!buffer.startsWith("/")) {
            return suggestFromHistory(buffer);
        }

        // Slash command suggestions
        String prefix = buffer.substring(1); // strip '/'

        // P1-8: If there's a space, suggest subcommands
        int spaceIdx = prefix.indexOf(' ');
        if (spaceIdx > 0) {
            return suggestSubcommand(buffer, prefix, spaceIdx);
        }

        // No space — suggest command name
        return suggestCommandName(prefix);
    }

    /**
     * Suggest a command name based on the prefix.
     */
    private String suggestCommandName(String prefix) {
        List<String> names = registry.getCommandNames();
        for (String name : names) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                return name.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * P1-8: Suggest a subcommand for commands like /memory, /tools, etc.
     */
    private String suggestSubcommand(String buffer, String prefix, int spaceIdx) {
        String cmdName = prefix.substring(0, spaceIdx);
        String afterSpace = prefix.substring(spaceIdx + 1);

        // If afterSpace is empty, suggest the first subcommand
        String[] subs = SUBCOMMANDS.get(cmdName);
        if (subs == null) return null;

        for (String sub : subs) {
            if (sub.startsWith(afterSpace) && sub.length() > afterSpace.length()) {
                return sub.substring(afterSpace.length());
            }
        }
        return null;
    }

    /**
     * P1-8: Suggest from input history (most recent matching entry).
     */
    private String suggestFromHistory(String buffer) {
        // Search from most recent to oldest
        for (int i = inputHistory.size() - 1; i >= 0; i--) {
            String entry = inputHistory.get(i);
            if (entry.startsWith(buffer) && entry.length() > buffer.length()) {
                return entry.substring(buffer.length());
            }
        }
        return null;
    }

    /**
     * Get all subcommands for a command name.
     */
    public static String[] getSubcommands(String commandName) {
        return SUBCOMMANDS.getOrDefault(commandName, new String[0]);
    }

    /**
     * Get the input history entries.
     */
    public List<String> getInputHistory() {
        return List.copyOf(inputHistory);
    }
}