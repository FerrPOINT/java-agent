package com.azhukov.agent.cli;

import org.jline.reader.impl.BufferImpl;

import java.util.List;

/**
 * Inline auto-suggest for slash commands.
 * <p>
 * When the user types "/", suggests the first matching command name
 * as a grayed-out inline suggestion (similar to fish shell).
 */
public class SlashAutoSuggest {

    private final SlashCommandRegistry registry;

    public SlashAutoSuggest(SlashCommandRegistry registry) {
        this.registry = registry;
    }

    /**
     * Get the best inline suggestion for the current input buffer.
     *
     * @param buffer the current input text
     * @return the suggested completion (without the typed part), or null
     */
    public String suggest(String buffer) {
        if (buffer == null || !buffer.startsWith("/")) {
            return null;
        }

        String prefix = buffer.substring(1); // strip '/'

        // If there's a space, don't suggest (command name is complete)
        if (prefix.contains(" ")) {
            return null;
        }

        List<String> names = registry.getCommandNames();
        for (String name : names) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                return name.substring(prefix.length());
            }
        }

        return null;
    }
}