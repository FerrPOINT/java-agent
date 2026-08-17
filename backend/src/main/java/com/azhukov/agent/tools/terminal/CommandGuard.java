package com.azhukov.agent.tools.terminal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates shell commands against a configurable set of dangerous patterns.
 *
 * <p>Improvements over naive {@code String.contains} matching:
 * <ul>
 *   <li>Normalises the command (collapses whitespace, trims) so that padding
 *       cannot bypass detection.</li>
 *   <li>Uses <b>regex</b> patterns instead of literal substrings, so variants like
 *       {@code rm -fr /} or {@code rm --force -r /} are caught.</li>
 *   <li>Checks both the full normalised command <em>and</em> individual shell tokens,
 *       so that a blocked keyword embedded in a longer word is not falsely flagged
 *       while a standalone token (e.g. {@code mkfs}) is reliably caught.</li>
 *   <li>{@code sudo} is blocked by default and configurable via
 *       {@code agent.terminal.block-sudo}.</li>
 *   <li>Additional patterns can be supplied via {@code agent.security.blocked-commands}
 *       in {@code application.yml}.</li>
 * </ul>
 */
public final class CommandGuard {

    /**
     * Built-in regex patterns that are always checked (cannot be emptied via config).
     * These cover the most dangerous destructive commands.
     */
    static final List<Pattern> DEFAULT_BLOCKED_PATTERNS = List.of(
        // rm with combined -rf/-fr flags targeting root /, root /*, home ~/ or home ~/*
        // The path must be exactly /, /*, ~/ or ~/* (not /tmp/... which is a specific path)
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*-(\\S*r\\S*f\\S*|\\S*f\\S*r\\S*)\\s+/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*-(\\S*r\\S*f\\S*|\\S*f\\S*r\\S*)\\s+~/(\\s|;|\\||&|$|\\*)"),
        // rm with separate -r and -f flags (any order) targeting root or home
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*-[A-Za-z]*r[A-Za-z]*\\s+(-\\S+\\s+)*-[A-Za-z]*f[A-Za-z]*\\s+/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*-[A-Za-z]*f[A-Za-z]*\\s+(-\\S+\\s+)*-[A-Za-z]*r[A-Za-z]*\\s+/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*-[A-Za-z]*r[A-Za-z]*\\s+(-\\S+\\s+)*-[A-Za-z]*f[A-Za-z]*\\s+~/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*-[A-Za-z]*f[A-Za-z]*\\s+(-\\S+\\s+)*-[A-Za-z]*r[A-Za-z]*\\s+~/(\\s|;|\\||&|$|\\*)"),
        // rm with mixed long/short flags (e.g. --force -r / or --recursive -f /)
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*--force\\s+(-\\S+\\s+)*-[A-Za-z]*r[A-Za-z]*\\s+/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*-[A-Za-z]*r[A-Za-z]*\\s+(-\\S+\\s+)*--force\\s+/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*--recursive\\s+(-\\S+\\s+)*-[A-Za-z]*f[A-Za-z]*\\s+/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*-[A-Za-z]*f[A-Za-z]*\\s+(-\\S+\\s+)*--recursive\\s+/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*--force\\s+(-\\S+\\s+)*-[A-Za-z]*r[A-Za-z]*\\s+~/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*-[A-Za-z]*r[A-Za-z]*\\s+(-\\S+\\s+)*--force\\s+~/(\\s|;|\\||&|$|\\*)"),
        // rm --recursive --force (and reverse order) targeting root or home
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*--recursive\\s+(-\\S+\\s+)*--force\\s+/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*--force\\s+(-\\S+\\s+)*--recursive\\s+/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*--recursive\\s+(-\\S+\\s+)*--force\\s+~/(\\s|;|\\||&|$|\\*)"),
        Pattern.compile("\\brm\\s+(-\\S+\\s+)*--force\\s+(-\\S+\\s+)*--recursive\\s+~/(\\s|;|\\||&|$|\\*)"),
        // mkfs (any variant)
        Pattern.compile("\\bmkfs\\b"),
        // dd if=/dev/zero|urandom to regular files is legitimate (common for creating
        // test files). Only block dd reading from or writing to BLOCK devices (sd*, nvme*, etc.)
        // dd if=/dev/sd... / nvme... (reading from block devices)
        Pattern.compile("\\bdd\\s+[^;]*if=/dev/(sd|nvme|hd|vd|xvd)"),
        // dd of=/dev/sd... / nvme... (writing to block devices)
        Pattern.compile("\\bdd\\s+[^;]*of=/dev/(sd|nvme|hd|vd|xvd)"),
        // Fork bomb: :(){ :|:& };: (with optional spaces everywhere)
        Pattern.compile(":\\s*\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
        // Redirect to block device: > /dev/sda
        Pattern.compile(">\\s*/dev/(sd|nvme|hd|vd|xvd)"),
        // shutdown / reboot / halt / poweroff
        Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b"),
        // Overwrite critical system files: > /etc/passwd, > /etc/shadow, etc.
        Pattern.compile(">\\s*/etc/(passwd|shadow|sudoers|fstab|crontab)\\b"),
        // kill -9 -1 (kill all processes)
        Pattern.compile("\\bkill\\s+-9\\s+-1\\b"),
        // iptables -F (flush all firewall rules)
        Pattern.compile("\\biptables\\s+-F\\b")
    );

    private final List<Pattern> compiledPatterns;
    private final boolean blockSudo;
    // P1-11: Shell hooks integration
    private final ShellHookManager shellHookManager;

    /**
     * Creates a guard with the given user-supplied regex patterns (in addition to
     * the built-in defaults) and sudo blocking flag.
     *
     * @param userPatterns additional regex patterns from configuration; may be null or empty
     * @param blockSudo    whether {@code sudo} as a leading token should be blocked
     */
    public CommandGuard(List<String> userPatterns, boolean blockSudo) {
        this(userPatterns, blockSudo, null);
    }

    /**
     * Creates a guard with the given user-supplied regex patterns, sudo blocking flag,
     * and an optional shell hook manager for event-based pre/post tool call hooks.
     *
     * <p>P1-11: When a {@link ShellHookManager} is provided, {@link #check(String)}
     * will also invoke registered {@code pre_tool_call} hooks before returning
     * a success verdict. If a hook returns a block decision, the command is blocked
     * with the hook's reason message.
     *
     * @param userPatterns      additional regex patterns from configuration; may be null or empty
     * @param blockSudo         whether {@code sudo} as a leading token should be blocked
     * @param shellHookManager  optional shell hook manager for event-based hooks; may be null
     */
    public CommandGuard(List<String> userPatterns, boolean blockSudo, ShellHookManager shellHookManager) {
        this.blockSudo = blockSudo;
        this.shellHookManager = shellHookManager;
        this.compiledPatterns = new ArrayList<>(DEFAULT_BLOCKED_PATTERNS);
        if (userPatterns != null) {
            for (String p : userPatterns) {
                if (p != null && !p.isBlank()) {
                    this.compiledPatterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
                }
            }
        }
    }

    /**
     * Checks the raw command and returns a failure message if it matches a blocked
     * pattern, or {@code null} if the command is allowed.
     *
     * <p>P1-11: Also invokes {@code pre_tool_call} shell hooks if a
     * {@link ShellHookManager} is registered. If any hook returns a block
     * decision, the hook's message is returned as the failure reason.
     *
     * @param rawCommand the command string as entered
     * @return error message if blocked, or {@code null} if allowed
     */
    public String check(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return null;
        }
        String normalised = normalise(rawCommand);

        // 1. Block sudo as a leading token if configured
        if (blockSudo && startsWithSudo(normalised)) {
            return "Blocked: 'sudo' is not allowed" + suggestAlternative("sudo");
        }

        // 2. Check regex patterns against the full normalised command
        for (Pattern p : compiledPatterns) {
            if (p.matcher(normalised).find()) {
                return "Blocked dangerous command pattern: " + p.pattern() + suggestAlternative(rawCommand);
            }
        }

        // 3. Token-based check: split on shell operators and whitespace, check each token
        // This catches cases where a single token matches a pattern exactly
        String[] tokens = shellTokens(normalised);
        for (String token : tokens) {
            for (Pattern p : compiledPatterns) {
                if (p.matcher(token).matches()) {
                    return "Blocked dangerous command token: " + token + suggestAlternative(rawCommand);
                }
            }
        }

        // 4. P1-11: Invoke pre_tool_call shell hooks
        if (shellHookManager != null) {
            ShellHookManager.HookResponse hookResponse = shellHookManager.invokePreToolCall("terminal", rawCommand);
            if (hookResponse != null && hookResponse.blocked()) {
                return "Blocked by shell hook: " + hookResponse.message() + suggestAlternative(rawCommand);
            }
        }

        return null;
    }

    /**
     * p12: Suggest alternative commands when a command is blocked.
     * Returns a formatted suggestion string appended to the block message.
     */
    private static String suggestAlternative(String rawCommand) {
        if (rawCommand == null) {
            return "";
        }
        String lower = rawCommand.toLowerCase().trim();
        StringBuilder sb = new StringBuilder();

        // rm -rf blocking → suggest trash or mv to /tmp
        if (lower.matches(".*\\brm\\s+(-\\S*\\s+)*-[a-z]*r[a-z]*f[a-z]*\\b.*|.*\\brm\\s+(-\\S*\\s+)*-[a-z]*f[a-z]*r[a-z]*\\b.*|.*\\brm\\s+(-\\S*\\s+)*--recursive\\b.*|.*\\brm\\s+(-\\S*\\s+)*--force\\b.*")) {
            sb.append(" — consider using 'trash' for safer deletion, or 'mv <file> /tmp/' to move to temp instead.");
        }
        // curl blocking (if blocked by user patterns) → suggest web_search tool
        if (lower.startsWith("curl ") || lower.startsWith("wget ")) {
            sb.append(" — consider using the web_search or web_extract tool instead.");
        }
        // shutdown/reboot/halt/poweroff → no real alternative but explain
        if (lower.matches(".*\\b(shutdown|reboot|halt|poweroff)\\b.*")) {
            sb.append(" — system power commands are blocked for safety.");
        }
        // mkfs → explain
        if (lower.startsWith("mkfs")) {
            sb.append(" — filesystem formatting is blocked. Use a loopback device or container if you need to test mkfs.");
        }
        // dd to block device → suggest writing to a file
        if (lower.startsWith("dd ") && (lower.contains("of=/dev/") || lower.contains("if=/dev/"))) {
            sb.append(" — consider writing to a regular file (e.g. of=/tmp/disk.img) instead of a block device.");
        }
        // kill -9 -1 → suggest killing specific PIDs
        if (lower.matches(".*\\bkill\\s+-9\\s+-1\\b.*")) {
            sb.append(" — consider killing specific process IDs instead of all processes.");
        }
        // iptables -F → explain
        if (lower.contains("iptables") && lower.contains("-f")) {
            sb.append(" — flushing firewall rules is blocked. Consider saving current rules with 'iptables-save' first.");
        }

        // Generic suggestion if nothing matched
        if (sb.isEmpty() && lower.startsWith("sudo ")) {
            sb.append(" — run the command without 'sudo' if the user has the required permissions.");
        }

        return sb.toString();
    }

    /**
     * P1-11: Notify all registered {@code post_tool_call} shell hooks that
     * a command has been executed. This should be called after the command
     * has run (regardless of success/failure).
     *
     * @param rawCommand the command that was executed
     * @param exitCode   the exit code of the command
     * @param stdout     the stdout output (may be truncated)
     */
    public void notifyPostExecution(String rawCommand, int exitCode, String stdout) {
        if (shellHookManager != null) {
            shellHookManager.invokePostToolCall("terminal", rawCommand, exitCode, stdout);
        }
    }

    /**
     * P1-11: Return the shell hook manager, if one is registered.
     *
     * @return the shell hook manager, or {@code null} if none is configured
     */
    public ShellHookManager getShellHookManager() {
        return shellHookManager;
    }

    /**
     * Normalise a command: collapse all whitespace runs to single spaces and trim.
     * This prevents bypass via extra spaces, tabs, or newlines between tokens.
     */
    static String normalise(String command) {
        return command.replaceAll("\\s+", " ").trim();
    }

    /**
     * Returns true if the normalised command starts with {@code sudo} as a token
     * (possibly preceded by env-assignments like {@code VAR=val sudo}).
     */
    static boolean startsWithSudo(String normalised) {
        return Pattern.compile("^(\\S+=\\S+\\s+)*sudo(\\s|$)").matcher(normalised).find();
    }

    /**
     * Split the command into tokens for per-token checking. Splits on whitespace
     * and common shell operators (|, &, ;, &&, ||, >, >>, <).
     */
    static String[] shellTokens(String normalised) {
        String[] parts = normalised.split("[\\s|;&<>]+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result.toArray(new String[0]);
    }
}