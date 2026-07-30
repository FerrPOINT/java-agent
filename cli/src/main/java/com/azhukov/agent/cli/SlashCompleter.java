package com.azhukov.agent.cli;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.Map;

/**
 * JLine Completer for slash commands.
 * <p>
 * When the user types "/", all available commands are shown.
 * When the user types "/comp", only commands starting with "comp" are shown.
 * Each candidate includes the command description in the popup.
 */
public class SlashCompleter implements Completer {

    private final SlashCommandRegistry registry;

    public SlashCompleter(SlashCommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null || line.word() == null) {
            return;
        }

        String word = line.word();

        // Only complete when the current word starts with '/'
        if (!word.startsWith("/")) {
            return;
        }

        String prefix = word.substring(1); // strip the '/'

        // Get sorted command names with descriptions
        Map<String, String> commandInfo = registry.getCommandDescriptions();
        for (Map.Entry<String, String> entry : commandInfo.entrySet()) {
            String name = entry.getKey();
            String desc = entry.getValue();

            // Filter by prefix
            if (prefix.isEmpty() || name.startsWith(prefix)) {
                String display = "/" + name;
                String descr = desc != null && !desc.isEmpty() ? desc : "";
                Candidate candidate = new Candidate(
                    "/" + name,   // value (what gets inserted)
                    display,       // display string
                    null,          // group
                    descr,         // descr (tooltip)
                    null,          // suffix
                    null,          // key
                    false          // complete
                );
                candidates.add(candidate);
            }
        }
    }
}