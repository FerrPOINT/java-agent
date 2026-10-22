package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.tool.FileMutationTracker;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds one correction pass when a Telegram turn creates files but its final
 * response has no existing local file reference for the delivery adapter.
 */
public final class FinalFileDeliveryGuard {

    public static final String NUDGE = """
        [System: This turn created files, but your final response does not contain an existing deliverable path.
        Before finalizing, identify only the file(s) the user asked to receive. If necessary, use a tool to locate the
        requested output and obtain its full path. On Telegram put each requested deliverable on its own line exactly
        as MEDIA:/absolute/path/to/file. Do not mention only a filename or a relative path. Do not infer or include
        intermediate files. If the requested output does not exist, state the concrete blocker instead of claiming
        it was delivered.]
        """;

    private static final Pattern PROTECTED_SPANS = Pattern.compile("(?s)```.*?```|`[^`\\n]+`");
    private static final Pattern MEDIA_PATH = Pattern.compile(
        "(?i)MEDIA:\\s*([~][/]|/|[A-Za-z]:[/\\\\])([^\\s`\\\"',;:)}\\]]+)");
    private static final Pattern BARE_PATH = Pattern.compile(
        "(?<![/:\\w.])((?:~/|/|[A-Za-z]:[/\\\\])[^\\s`\\\"',;:)}\\]]+)");

    private FinalFileDeliveryGuard() {
    }

    public static boolean shouldNudge(Session session, String finalContent, FileMutationTracker tracker) {
        if (session == null || tracker == null
            || !"telegram".equalsIgnoreCase(session.getMetadata("platform"))
            || hasExistingFinalReference(finalContent)) {
            return false;
        }
        return tracker.reserveFinalFileDeliveryNudge();
    }

    public static boolean hasExistingFinalReference(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String visible = PROTECTED_SPANS.matcher(content).replaceAll(" ");
        return containsExistingPath(visible, MEDIA_PATH, true) || containsExistingPath(visible, BARE_PATH, false);
    }

    private static boolean containsExistingPath(String content, Pattern pattern, boolean mediaDirective) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String path = mediaDirective ? matcher.group(1) + matcher.group(2) : matcher.group(1);
            if (isRegularFile(path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRegularFile(String rawPath) {
        String path = rawPath.startsWith("~/")
            ? System.getProperty("user.home") + rawPath.substring(1)
            : rawPath;
        try {
            return Files.isRegularFile(Path.of(path));
        } catch (InvalidPathException ignored) {
            return false;
        }
    }
}
