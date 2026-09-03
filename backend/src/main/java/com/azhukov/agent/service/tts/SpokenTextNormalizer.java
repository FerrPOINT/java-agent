package com.azhukov.agent.service.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lightweight deterministic cleanup for text sent to speech providers.
 */
public final class SpokenTextNormalizer {

    private static final String HEAD = "\u0000";

    private static final Pattern THINK_BLOCK =
        Pattern.compile("<think[\\s>].*?</think>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THINK_BLOCK_OPEN =
        Pattern.compile("<think[\\s>].*\\z", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern VERIFIER_FOOTER =
        Pattern.compile("^\\s*(?:\\u26A0\\uFE0F?\\s*)?File-mutation verifier:.*(?:\\R[ \\t]+\\u2022.*)*",
            Pattern.MULTILINE);

    private static final Pattern MD_CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern MD_IMAGE =
        Pattern.compile("!\\[([^\\]]*)]\\((?:[^()]|\\([^)]*\\))*\\)");
    private static final Pattern MD_LINK =
        Pattern.compile("\\[([^\\]]+)]\\((?:[^()]|\\([^)]*\\))*\\)");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern MD_INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*", Pattern.DOTALL);
    private static final Pattern MD_UNDERSCORE_BOLD = Pattern.compile("__(.+?)__", Pattern.DOTALL);
    private static final Pattern MD_ITALIC =
        Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", Pattern.DOTALL);
    private static final Pattern MD_UNDERSCORE_ITALIC =
        Pattern.compile("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)", Pattern.DOTALL);
    private static final Pattern MD_STRIKE = Pattern.compile("~~(.+?)~~", Pattern.DOTALL);
    private static final Pattern MD_HEADING =
        Pattern.compile("^[ \\t]{0,3}#{1,6}[ \\t]+(.+?)[ \\t]*#*[ \\t]*$", Pattern.MULTILINE);
    private static final Pattern MD_BLOCKQUOTE = Pattern.compile("^\\s*>\\s?", Pattern.MULTILINE);
    private static final Pattern MD_LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+", Pattern.MULTILINE);
    private static final Pattern MD_HR = Pattern.compile("^\\s*[-*_]{3,}\\s*$", Pattern.MULTILINE);
    private static final Pattern MD_TABLE_PIPE = Pattern.compile("\\s*\\|\\s*");
    private static final Pattern NBSP = Pattern.compile("[\\u00A0\\u2007\\u202F]");
    private static final Pattern EXCESS_NL = Pattern.compile("\\n{3,}");
    private static final Pattern MANY_SPACES = Pattern.compile("[ \\t]{2,}");

    private SpokenTextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String spoken = stripNonspokenBlocks(text);
        spoken = stripMarkdown(spoken);
        spoken = normalizeSymbols(spoken);
        spoken = stripEmojiLike(spoken);
        spoken = smoothWhitespace(spoken);
        spoken = flattenNewlines(spoken);
        return spoken.trim();
    }

    private static String stripNonspokenBlocks(String text) {
        String spoken = THINK_BLOCK.matcher(text).replaceAll(" ");
        spoken = THINK_BLOCK_OPEN.matcher(spoken).replaceAll(" ");
        return VERIFIER_FOOTER.matcher(spoken).replaceAll(" ");
    }

    private static String stripMarkdown(String text) {
        String spoken = text;
        spoken = MD_CODE_BLOCK.matcher(spoken).replaceAll(" ");
        spoken = MD_IMAGE.matcher(spoken).replaceAll(match -> {
            String alt = match.group(1);
            return alt == null || alt.isBlank() ? " " : " " + alt + " ";
        });
        spoken = MD_LINK.matcher(spoken).replaceAll("$1");
        spoken = URL.matcher(spoken).replaceAll("");
        spoken = MD_INLINE_CODE.matcher(spoken).replaceAll("$1");
        spoken = MD_BOLD.matcher(spoken).replaceAll("$1");
        spoken = MD_UNDERSCORE_BOLD.matcher(spoken).replaceAll("$1");
        spoken = MD_ITALIC.matcher(spoken).replaceAll("$1");
        spoken = MD_UNDERSCORE_ITALIC.matcher(spoken).replaceAll("$1");
        spoken = MD_STRIKE.matcher(spoken).replaceAll("$1");
        spoken = MD_HEADING.matcher(spoken).replaceAll(match -> match.group(1).stripTrailing() + HEAD);
        spoken = MD_BLOCKQUOTE.matcher(spoken).replaceAll("");
        spoken = MD_LIST_ITEM.matcher(spoken).replaceAll("");
        spoken = MD_HR.matcher(spoken).replaceAll("");
        return MD_TABLE_PIPE.matcher(spoken).replaceAll("; ");
    }

    private static String normalizeSymbols(String text) {
        String spoken = NBSP.matcher(text).replaceAll(" ");
        spoken = spoken.replace('\u2212', '-');
        spoken = spoken.replace("\u2026", "...");
        spoken = normalizeTemperatureRanges(spoken);
        spoken = spoken.replaceAll("(?i)(?<!\\w)([-+]?\\d+(?:\\.\\d+)?)\\s*\\u00B0\\s*C\\b", "$1 degrees Celsius");
        spoken = spoken.replaceAll("(?i)(?<!\\w)([-+]?\\d+(?:\\.\\d+)?)\\s*\\u00B0\\s*F\\b", "$1 degrees Fahrenheit");
        spoken = spoken.replaceAll("(?i)\\u00B0\\s*C\\b", "degrees Celsius");
        spoken = spoken.replaceAll("(?i)\\u00B0\\s*F\\b", "degrees Fahrenheit");
        spoken = spoken.replaceAll("(?<!\\w)([-+]?\\d+(?:\\.\\d+)?)\\s*\\u00B0", "$1 degrees");
        spoken = spoken.replace("\u00B0", " degrees");
        spoken = spoken.replaceAll("(?i)(?<=\\d)\\s*km\\s*/\\s*h\\b", " kilometres per hour");
        spoken = spoken.replaceAll("(?i)(?<=\\d)\\s*km/h\\b", " kilometres per hour");
        spoken = spoken.replaceAll("(?i)(?<=\\d)\\s*mm\\b", " millimetres");
        spoken = spoken.replaceAll("(?i)(?<=\\d)\\s*cm\\b", " centimetres");
        spoken = spoken.replaceAll("(?i)(?<=\\d)\\s*m\\b", " metres");
        spoken = spoken.replaceAll("(?<=\\d)\\s*/\\s*(?=[A-Za-z])", " per ");
        spoken = spoken.replaceAll("(?i)NZ\\$\\s*([\\d,]*\\d(?:\\.\\d+)?)", "$1 New Zealand dollars");
        spoken = spoken.replaceAll("(?i)A\\$\\s*([\\d,]*\\d(?:\\.\\d+)?)", "$1 Australian dollars");
        spoken = spoken.replaceAll("(?i)US\\$\\s*([\\d,]*\\d(?:\\.\\d+)?)", "$1 US dollars");
        spoken = spoken.replaceAll("\\u20AC\\s*([\\d,]*\\d(?:\\.\\d+)?)", "$1 euros");
        spoken = spoken.replaceAll("\\u00A3\\s*([\\d,]*\\d(?:\\.\\d+)?)", "$1 pounds");
        spoken = spoken.replaceAll("\\$\\s*([\\d,]*\\d(?:\\.\\d+)?)", "$1 dollars");
        spoken = spoken.replaceAll("(?<=\\d)\\s*%", " percent");
        spoken = spoken.replace("&", " and ");
        spoken = spoken.replaceAll("[\\u2022\\u25E6\\u25AA\\u25AB]", " ");
        spoken = spoken.replace("\u2192", " to ");
        spoken = spoken.replace("\u21D2", " to ");
        spoken = spoken.replace("\u2248", " about ");
        spoken = spoken.replace("~", " about ");
        return spoken;
    }

    private static String normalizeTemperatureRanges(String text) {
        String spoken = text.replaceAll(
            "(?i)(?<!\\w)([-+]?\\d+(?:\\.\\d+)?)\\s*[\\u2013\\u2014-]\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*\\u00B0\\s*C\\b",
            "$1 to $2 degrees Celsius"
        );
        return spoken.replaceAll(
            "(?i)(?<!\\w)([-+]?\\d+(?:\\.\\d+)?)\\s*[\\u2013\\u2014-]\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*\\u00B0\\s*F\\b",
            "$1 to $2 degrees Fahrenheit"
        );
    }

    private static String stripEmojiLike(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints()
            .filter(codePoint -> !isEmojiLike(codePoint))
            .forEach(out::appendCodePoint);
        return out.toString();
    }

    private static boolean isEmojiLike(int codePoint) {
        return (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF)
            || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
            || (codePoint >= 0x2600 && codePoint <= 0x27BF)
            || codePoint == 0xFE0F
            || codePoint == 0x200D
            || (codePoint >= 0xE0020 && codePoint <= 0xE007F);
    }

    private static String smoothWhitespace(String text) {
        String[] rawLines = text.split("\\R", -1);
        boolean addSentencePauses = false;
        for (String rawLine : rawLines) {
            if (!rawLine.replace(HEAD, "").trim().isEmpty()) {
                if (addSentencePauses) {
                    break;
                }
                addSentencePauses = true;
            }
        }
        int nonEmptyLines = 0;
        for (String rawLine : rawLines) {
            if (!rawLine.replace(HEAD, "").trim().isEmpty()) {
                nonEmptyLines++;
            }
        }
        addSentencePauses = nonEmptyLines > 1;

        List<String> lines = new ArrayList<>();
        String pendingHeading = null;
        for (String rawLine : rawLines) {
            boolean isHeading = rawLine.stripTrailing().endsWith(HEAD);
            String line = rawLine.replace(HEAD, "").trim();
            if (line.isEmpty()) {
                if (pendingHeading == null && !lines.isEmpty() && !lines.getLast().isEmpty()) {
                    lines.add("");
                }
                continue;
            }
            if (isHeading) {
                if (pendingHeading != null) {
                    lines.add(trimEndPunctuation(pendingHeading) + ".");
                }
                pendingHeading = trimEndPunctuation(line);
                continue;
            }
            if (pendingHeading != null) {
                line = trimEndPunctuation(pendingHeading) + ", " + line;
                pendingHeading = null;
            }
            if (addSentencePauses && !endsWithPause(line)) {
                line += ".";
            }
            lines.add(line);
        }
        if (pendingHeading != null) {
            lines.add(trimEndPunctuation(pendingHeading) + ".");
        }
        String spoken = String.join("\n", lines);
        spoken = EXCESS_NL.matcher(spoken).replaceAll("\n\n");
        spoken = MANY_SPACES.matcher(spoken).replaceAll(" ");
        spoken = spoken.replaceAll("\\s+([,.;:!?])", "$1");
        spoken = spoken.replaceAll("([,.;:!?])([A-Za-z])", "$1 $2");
        spoken = spoken.replaceAll("\\.{4,}", "...");
        return spoken.trim();
    }

    private static boolean endsWithPause(String line) {
        char last = line.charAt(line.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == ';' || last == ':';
    }

    private static String trimEndPunctuation(String value) {
        return value.replaceAll("[.:;,]+$", "");
    }

    private static String flattenNewlines(String text) {
        String spoken = text.replaceAll("\\n{2,}", ". ");
        spoken = spoken.replaceAll("(?<=[.!?;:,])\\n", " ");
        spoken = spoken.replace("\n", ". ");
        spoken = spoken.replaceAll("\\.\\s*\\.", ".");
        spoken = MANY_SPACES.matcher(spoken).replaceAll(" ");
        return spoken.trim();
    }
}
