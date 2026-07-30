package com.azhukov.agent.cli;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.builtins.Completers;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * JLine Completer for slash commands and file paths.
 * <p>
 * P1-6: When the user types a bare path (starts with / or . or ~), complete
 * with local filesystem entries using JLine's {@link FileNameCompleter}.
 * <p>
 * Also completes @-references (@diff, @file:, @url:, etc.) for P1-7.
 */
public class SlashCompleter implements Completer {

    private final SlashCommandRegistry registry;
    private final Completers.FileNameCompleter fileCompleter;

    // P1-7: @-reference candidates
    private static final String[] AT_REFS = {
        "@diff", "@staged", "@git", "@file:", "@folder:", "@url:"
    };

    public SlashCompleter(SlashCommandRegistry registry) {
        this.registry = registry;
        this.fileCompleter = new Completers.FileNameCompleter();
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null || line.word() == null) {
            return;
        }

        String word = line.word();

        // P1-7: Complete @-references
        if (word.startsWith("@")) {
            String prefix = word;
            for (String ref : AT_REFS) {
                if (ref.startsWith(prefix) && ref.length() > prefix.length()) {
                    candidates.add(new Candidate(
                        ref, ref, null, ref, null, null, false
                    ));
                }
            }
            return;
        }

        // Complete slash commands
        if (word.startsWith("/")) {
            String prefix = word.substring(1); // strip the '/'

            // P1-6: File path completion — if the word after / looks like a path
            // (contains / or . or starts with ~), use file completion instead
            if (prefix.contains("/") || prefix.startsWith(".") || prefix.startsWith("~")) {
                fileCompleter.complete(reader, line, candidates);
                return;
            }

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
            return;
        }

        // P1-6: Complete bare file paths (words that start with . or ~ or /)
        // JLine's FileNameCompleter handles this
        if (word.startsWith(".") || word.startsWith("~") ||
            (word.length() > 1 && word.startsWith("/"))) {
            fileCompleter.complete(reader, line, candidates);
        }
    }
}