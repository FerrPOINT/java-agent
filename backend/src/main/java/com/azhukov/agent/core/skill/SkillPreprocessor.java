package com.azhukov.agent.core.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S14: Skill preprocessing — template variable substitution.
 * <p>
 * Supports:
 * - ${SESSION_ID} template variable in skill content
 * - Inline shell expansion `!command` syntax (for !`command`)
 * <p>
 * Ported from Hermes' skill_preprocessing.py (simplified).
 */
@Slf4j
@Component
public class SkillPreprocessor {

    // S14: Template variable patterns
    private static final Pattern TEMPLATE_VAR_RE = Pattern.compile("\\$\\{(SESSION_ID|SKILL_DIR)\\}");
    private static final Pattern INLINE_SHELL_RE = Pattern.compile("!`([^`\n]+)`");

    // Cap inline shell output
    private static final int INLINE_SHELL_MAX_OUTPUT = 4000;
    private static final int INLINE_SHELL_TIMEOUT_SECONDS = 10;

    private volatile boolean enabled = true;

    /**
     * S14: Set whether preprocessing is enabled.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * S14: Preprocess skill content — substitute template variables and inline shell.
     *
     * @param content   the skill content to preprocess
     * @param sessionId the current session ID (for ${SESSION_ID} substitution)
     * @param skillDir  the skill directory path (for ${SKILL_DIR} substitution)
     * @return preprocessed content
     */
    public String preprocess(String content, String sessionId, String skillDir) {
        if (content == null || content.isEmpty() || !enabled) {
            return content;
        }

        // S14: Substitute template variables
        content = substituteTemplateVars(content, sessionId, skillDir);

        // S14: Inline shell expansion
        content = expandInlineShell(content);

        return content;
    }

    /**
     * S14: Substitute ${SESSION_ID} and ${SKILL_DIR} template variables.
     * Unresolved tokens are left as-is.
     */
    private String substituteTemplateVars(String content, String sessionId, String skillDir) {
        Matcher matcher = TEMPLATE_VAR_RE.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = switch (token) {
                case "SESSION_ID" -> sessionId != null ? sessionId : matcher.group(0);
                case "SKILL_DIR" -> skillDir != null ? skillDir : matcher.group(0);
                default -> matcher.group(0);
            };
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * S14: Expand inline shell snippets like !`date +%Y-%m-%d`.
     * Failures return a short error marker instead of raising.
     */
    private String expandInlineShell(String content) {
        if (!content.contains("!`")) {
            return content;
        }
        Matcher matcher = INLINE_SHELL_RE.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String command = matcher.group(1);
            String output = runInlineShell(command);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(output));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * S14: Execute a single inline-shell snippet and return its stdout (trimmed).
     */
    private String runInlineShell(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            pb.redirectErrorStream(false);
            Process process = pb.start();
            boolean finished = process.waitFor(INLINE_SHELL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "[inline-shell timeout after " + INLINE_SHELL_TIMEOUT_SECONDS + "s: " + command + "]";
            }
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (output.isEmpty()) {
                String stderr = new String(process.getErrorStream().readAllBytes()).trim();
                if (!stderr.isEmpty()) output = stderr;
            }
            if (output.length() > INLINE_SHELL_MAX_OUTPUT) {
                output = output.substring(0, INLINE_SHELL_MAX_OUTPUT) + "...[truncated]";
            }
            return output;
        } catch (Exception e) {
            if (e instanceof java.util.concurrent.TimeoutException) {
                return "[inline-shell timeout after " + INLINE_SHELL_TIMEOUT_SECONDS + "s: " + command + "]";
            }
            return "[inline-shell error: " + e.getMessage() + "]";
        }
    }
}