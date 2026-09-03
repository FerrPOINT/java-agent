package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.tools.terminal.CommandGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S2: Skill preprocessing — template variable substitution and inline shell expansion.
 * <p>
 * Supports:
 * - ${HERMES_SKILL_DIR} / ${HERMES_SESSION_ID} template variables (template convention)
 * - Backward compat: ${SESSION_ID} → ${HERMES_SESSION_ID}, ${SKILL_DIR} → ${HERMES_SKILL_DIR}
 * - Inline shell expansion !`command` syntax with CWD set to skill directory
 * <p>
 * Ported from the original project's skill_preprocessing.py.
 */
@Slf4j
@Component
public class SkillPreprocessor {

    @Autowired(required = false)
    private AgentProperties agentProperties;

    // S2: Template variable patterns — template convention uses HERMES_ prefix
    private static final Pattern TEMPLATE_VAR_RE = Pattern.compile("\\$\\{(HERMES_SKILL_DIR|HERMES_SESSION_ID|SESSION_ID|SKILL_DIR)\\}");
    private static final Pattern INLINE_SHELL_RE = Pattern.compile("!`([^`\n]+)`");

    // Cap inline shell output
    private static final int INLINE_SHELL_MAX_OUTPUT = 4000;
    private static final int INLINE_SHELL_TIMEOUT_SECONDS = 10;

    private volatile boolean enabled = true;
    private volatile boolean inlineShellEnabled = false;
    private volatile int inlineShellTimeout = INLINE_SHELL_TIMEOUT_SECONDS;
    private volatile CommandGuard commandGuard = new CommandGuard(List.of(), false);

    /**
     * H8: Read inline shell settings from AgentProperties.skills.
     * Wired as @PostConstruct so the Spring-managed bean picks up config on startup.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @jakarta.annotation.PostConstruct
    void init() {
        if (agentProperties != null && agentProperties.getSkills() != null) {
            AgentProperties.SkillsProperties skills = agentProperties.getSkills();
            this.inlineShellEnabled = skills.isInlineShell();
            this.inlineShellTimeout = skills.getInlineShellTimeout();
        }
    }

 /**
  * Set the CommandGuard used to validate inline shell commands before execution.
  * Defaults to a permissive guard (no blocked patterns, sudo allowed).
  */
 public void setCommandGuard(CommandGuard guard) {
     this.commandGuard = guard;
 }

 /**
 * S2: Set whether preprocessing is enabled.
 */
 public void setEnabled(boolean enabled) {
 this.enabled = enabled;
 }

 public boolean isEnabled() {
 return enabled;
 }

 public void setInlineShellEnabled(boolean enabled) {
 this.inlineShellEnabled = enabled;
 }

 public boolean isInlineShellEnabled() {
 return inlineShellEnabled;
 }

 public void setInlineShellTimeout(int timeout) {
 this.inlineShellTimeout = timeout;
 }

 /**
 * S2: Preprocess skill content — substitute template variables and inline shell.
 *
 * @param content the skill content to preprocess
 * @param sessionId the current session ID (for ${HERMES_SESSION_ID} substitution)
 * @param skillDir the skill directory path (for ${HERMES_SKILL_DIR} substitution)
 * @return preprocessed content
 */
 public String preprocess(String content, String sessionId, String skillDir) {
 if (content == null || content.isEmpty() || !enabled) {
 return content;
 }

 // H-SYNC: Strip leading UTF-8 BOM (Windows editors) before the fence.
 // Mirrors Hermes skill_manager_tool.py: content = content.lstrip("\ufeff")
 if (content.charAt(0) == '\uFEFF') {
 content = content.substring(1);
 }

 // S2: Substitute template variables (with backward compat)
 content = substituteTemplateVars(content, sessionId, skillDir);

 // S2: Inline shell expansion (only if enabled)
 if (inlineShellEnabled) {
 content = expandInlineShell(content, skillDir);
 }

 return content;
 }

 /**
 * S2: Substitute ${HERMES_SKILL_DIR} and ${HERMES_SESSION_ID} template variables.
 * Also supports backward-compatible ${SESSION_ID} and ${SKILL_DIR}.
 * Unresolved tokens are left as-is.
 */
 String substituteTemplateVars(String content, String sessionId, String skillDir) {
 Matcher matcher = TEMPLATE_VAR_RE.matcher(content);
 StringBuilder sb = new StringBuilder();
 while (matcher.find()) {
 String token = matcher.group(1);
 String replacement = switch (token) {
 case "HERMES_SESSION_ID", "SESSION_ID" -> sessionId != null ? sessionId : matcher.group(0);
 case "HERMES_SKILL_DIR", "SKILL_DIR" -> skillDir != null ? skillDir : matcher.group(0);
 default -> matcher.group(0);
 };
 matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
 }
 matcher.appendTail(sb);
 return sb.toString();
 }

 /**
 * S2: Expand inline shell snippets like !`date +%Y-%m-%d`.
 * Failures return a short error marker instead of raising.
 * The skill directory is used as CWD so relative paths work.
 */
 String expandInlineShell(String content, String skillDir) {
 if (!content.contains("!`")) {
 return content;
 }
 Matcher matcher = INLINE_SHELL_RE.matcher(content);
 StringBuilder sb = new StringBuilder();
 while (matcher.find()) {
 String command = matcher.group(1).trim();
 if (command.isEmpty()) {
 continue;
 }
 String output = runInlineShell(command, skillDir);
 matcher.appendReplacement(sb, Matcher.quoteReplacement(output));
 }
 matcher.appendTail(sb);
 return sb.toString();
 }

 /**
 * S2: Execute a single inline-shell snippet and return its stdout (trimmed).
 * S2 FIX: Sets CWD to skillDir so relative paths in shell snippets work.
 */
 String runInlineShell(String command, String skillDir) {
     int timeout = Math.max(1, inlineShellTimeout);
     // Validate command against CommandGuard before execution
     String blockReason = commandGuard.check(command);
     if (blockReason != null) {
         log.warn("Blocked inline shell command by CommandGuard: {} — {}", command, blockReason);
         return "[inline-shell blocked by CommandGuard: " + blockReason + "]";
     }
     try {
         ProcessBuilder pb = new ProcessBuilder(inlineShellCommand(command));
         pb.redirectErrorStream(false);
         // S2 FIX: Set working directory to skill dir so relative paths work.
         // Windows cannot use POSIX paths like /tmp as a ProcessBuilder cwd; ignore
         // invalid directories instead of failing before the command can run.
         if (skillDir != null && !skillDir.isBlank()) {
             File cwd = new File(skillDir);
             if (cwd.isDirectory()) {
                 pb.directory(cwd);
             } else {
                 log.debug("Inline shell skill directory does not exist, using process cwd: {}", skillDir);
             }
         }
         Process process = pb.start();
         process.getOutputStream().close();
         boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
         if (!finished) {
             process.destroyForcibly();
             return "[inline-shell timeout after " + timeout + "s: " + command + "]";
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
             return "[inline-shell timeout after " + timeout + "s: " + command + "]";
         }
         return "[inline-shell error: " + e.getMessage() + "]";
     }
 }

 private List<String> inlineShellCommand(String command) {
     if (isWindows()) {
         String comspec = System.getenv("ComSpec");
         String shell = comspec != null && !comspec.isBlank() ? comspec : "cmd.exe";
         return List.of(shell, "/d", "/c", command);
     }
     return List.of("bash", "-c", command);
 }

 private boolean isWindows() {
     return System.getProperty("os.name", "").toLowerCase().contains("win");
 }
}
