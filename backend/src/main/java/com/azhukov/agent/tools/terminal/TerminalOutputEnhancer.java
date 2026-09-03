package com.azhukov.agent.tools.terminal;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhances raw terminal command output with helpful UX annotations:
 * <ul>
 *   <li><b>CWD echo</b> (p4) — appends {@code [cwd: /path]} after each command.</li>
 *   <li><b>Error hints</b> (p5) — detects common error patterns and appends actionable hints.</li>
 *   <li><b>Timeout clarity</b> (p6) — appends a background-mode hint when a command times out.</li>
 *   <b>Signal-termination exit codes</b> (h47) — interprets exit codes > 128 as signal numbers.</li>
 *   <li><b>Exit_code 0 masks piped failure</b> (h48) — warns when exit code is 0 but output contains error indicators.</li>
 *   <li><b>CWD unenterable fallback</b> (h49) — falls back to /tmp or user home when configured workdir is inaccessible.</li>
 * </ul>
 */
@Slf4j
public final class TerminalOutputEnhancer {

    private TerminalOutputEnhancer() {
    }

    // ── Error patterns (p5) ──────────────────────────────────────────────

    private static final Pattern COMMAND_NOT_FOUND =
        Pattern.compile("command not found\\s*:\\s*(\\S+)|\\b(\\S+):\\s*command not found", Pattern.CASE_INSENSITIVE);

    private static final Pattern MODULE_NOT_FOUND =
        Pattern.compile("ModuleNotFoundError:\\s*No module named\\s+'?([^'\\s']+)'?", Pattern.CASE_INSENSITIVE);

    private static final Pattern PERMISSION_DENIED =
        Pattern.compile("Permission denied", Pattern.CASE_INSENSITIVE);

    private static final Pattern NO_SUCH_FILE =
        Pattern.compile("No such file or directory", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONNECTION_REFUSED =
        Pattern.compile("Connection refused", Pattern.CASE_INSENSITIVE);

    // ── Error-indicator patterns for exit-code-0 masking (h48) ───────────

    private static final Pattern[] ERROR_INDICATORS = {
        Pattern.compile("\\bFAILED\\b"),
        Pattern.compile("\\bERROR:"),
        Pattern.compile("\\bBUILD FAILED\\b"),
        Pattern.compile("(?i)\\btests?\\s+failed\\b"),
        Pattern.compile("\\bPANIC\\b")
    };

    // ── Signal map (h47) ──────────────────────────────────────────────────

    private static final Map<Integer, String> SIGNALS = new LinkedHashMap<>();

    static {
        SIGNALS.put(1, "SIGHUP");
        SIGNALS.put(2, "SIGINT");
        SIGNALS.put(3, "SIGQUIT");
        SIGNALS.put(6, "SIGABRT");
        SIGNALS.put(9, "SIGKILL");
        SIGNALS.put(11, "SIGSEGV");
        SIGNALS.put(13, "SIGPIPE");
        SIGNALS.put(14, "SIGALRM");
        SIGNALS.put(15, "SIGTERM");
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Enhances raw command output.  The {@code timedOut} flag is separate from
     * {@code exitCode} because when a process is forcibly killed after timeout
     * the exit code may be a signal code (137 = 128 + 9 for SIGKILL) or simply
     * unavailable.
     *
     * @param rawOutput  the raw command output (stdout + stderr, already redacted)
     * @param exitCode   the process exit code, or {@code -1} if unavailable
     * @param workdir    the configured working directory, or {@code null}/{@code blank} if none
     * @param timedOut   {@code true} if the command timed out
     * @param actualCwd  the actual working directory the process ran in (for CWD echo),
     *                   or {@code null} to derive from {@code workdir}
     * @return the enhanced output with appended annotations
     */
    public static String enhance(String rawOutput, int exitCode, String workdir,
                                 boolean timedOut, String actualCwd) {
        StringBuilder sb = new StringBuilder();
        if (rawOutput != null && !rawOutput.isEmpty()) {
            sb.append(rawOutput);
        }

        // h49: CWD unenterable fallback — handled by resolveWorkdir(), but if
        // actualCwd differs from workdir we emit a warning.
        String effectiveWorkdir = resolveWorkdir(workdir);
        String cwdForEcho = actualCwd != null ? actualCwd : effectiveWorkdir;

        if (workdir != null && !workdir.isBlank()) {
            File configured = new File(workdir);
            if (!configured.isDirectory()) {
                sb.append("\n[warning: configured workdir '")
                  .append(workdir)
                  .append("' is not accessible, using '")
                  .append(cwdForEcho)
                  .append("' instead]");
            }
        }

        // p4: CWD echo
        if (cwdForEcho != null && !cwdForEcho.isBlank()) {
            sb.append("\n[cwd: ").append(cwdForEcho).append("]");
        }

        // p5: Error hints
        String hints = detectErrorHints(rawOutput);
        if (hints != null) {
            sb.append(hints);
        }

        // p6: Timeout clarity
        if (timedOut) {
            sb.append("\n[hint: for long-running commands, use background=true parameter]");
        }

        // h47: Signal-termination exit codes
        if (exitCode > 128) {
            int signal = exitCode - 128;
            String signalName = SIGNALS.getOrDefault(signal, "SIGNAL_" + signal);
            sb.append("\n[signal: ").append(signalName).append(" (").append(signal).append(")]");
        }

        // h48: Exit code 0 masks piped failure
        if (exitCode == 0 && containsErrorIndicators(rawOutput)) {
            sb.append("\n[warning: exit code 0 but output contains error indicators — check output carefully]");
        }

        return sb.toString();
    }

    /** Convenience overload — no timeout, cwd derived from workdir. */
    public static String enhance(String rawOutput, int exitCode, String workdir) {
        return enhance(rawOutput, exitCode, workdir, false, null);
    }


    // ── Exit-code semantics (Hermes _interpret_exit_code parity) ────────

    /**
     * Interpret non-zero exit codes that are not real errors, mirroring
     * Hermes terminal_tool._interpret_exit_code: grep=1 means "no matches",
     * diff=1 means "files differ", etc. Returns a short informational note
     * appended to the output, or null when the code is a real error.
     *
     * <p>Failure-loop detection keys off the failed flag; interpreting
     * these codes as normal prevents false "Tool loop warning" firing when
     * the agent runs a few greps that find nothing.
     */
    public static String interpretExitCode(String command, int exitCode) {
        if (exitCode == 0 || command == null || command.isBlank()) {
            return null;
        }
        // Take the last pipeline segment, then the first word that is not
        // a VAR=val assignment — the effective base command.
        String[] segments = command.split("(?:\\|\\||&&|[|;])");
        String lastSegment = segments.length > 0 ? segments[segments.length - 1].trim() : command;
        String baseCmd = null;
        for (String w : lastSegment.split("\\s+")) {
            if (w.contains("=") && !w.startsWith("-")) {
                continue; // skip VAR=val
            }
            baseCmd = w.substring(w.lastIndexOf('/') + 1);
            break;
        }
        if (baseCmd == null || baseCmd.isEmpty()) {
            return null;
        }
        switch (baseCmd) {
            case "grep", "egrep", "fgrep", "rg", "ag", "ack":
                return exitCode == 1 ? "No matches found (not an error)" : null;
            case "diff", "colordiff":
                return exitCode == 1 ? "Files differ (expected, not an error)" : null;
            case "find":
                return exitCode == 1 ? "Some directories were inaccessible (partial results may still be valid)" : null;
            case "test", "[":
                return exitCode == 1 ? "Condition evaluated to false (expected, not an error)" : null;
            case "curl":
                return switch (exitCode) {
                    case 6 -> "Could not resolve host";
                    case 7 -> "Failed to connect to host";
                    case 22 -> "HTTP response code indicated error (e.g. 404, 500)";
                    case 28 -> "Operation timed out";
                    default -> null;
                };
            case "git":
                return exitCode == 1 ? "Non-zero exit (often normal - e.g. 'git diff' returns 1 when files differ)" : null;
            default:
                return null;
        }
    }

    // ── Workdir resolution (h49) ─────────────────────────────────────────

    /**
     * Resolves the effective working directory.  If the configured workdir is
     * non-blank but inaccessible, falls back to {@code /tmp} or the user's home
     * directory.
     *
     * @return the resolved directory path, or the system temp dir as a last resort
     */
    public static String resolveWorkdir(String workdir) {
        if (workdir == null || workdir.isBlank()) {
            // No workdir specified — use user home or /tmp
            String home = System.getProperty("user.home");
            if (home != null && new File(home).isDirectory()) {
                return home;
            }
            return System.getProperty("java.io.tmpdir", "/tmp");
        }
        File dir = new File(workdir);
        if (dir.isDirectory()) {
            return dir.getAbsolutePath();
        }
        // Fallback: /tmp, then user home
        File tmp = new File(System.getProperty("java.io.tmpdir", "/tmp"));
        if (tmp.isDirectory()) {
            return tmp.getAbsolutePath();
        }
        String home = System.getProperty("user.home");
        if (home != null && new File(home).isDirectory()) {
            return home;
        }
        return "/tmp";
    }

    /**
     * Returns the {@link File} for the resolved workdir, or {@code null} if the
     * caller should not set a directory on the {@link ProcessBuilder}.
     */
    public static File resolveWorkdirFile(String workdir) {
        String resolved = resolveWorkdir(workdir);
        File f = new File(resolved);
        return f.isDirectory() ? f : null;
    }

    // ── Error hint detection (p5) ───────────────────────────────────────

    static String detectErrorHints(String output) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        StringBuilder hints = new StringBuilder();

        Matcher m = COMMAND_NOT_FOUND.matcher(output);
        if (m.find()) {
            String cmd = m.group(1) != null ? m.group(1) : m.group(2);
            hints.append("\n[hint: install ")
                 .append(cmd)
                 .append(" or check PATH]");
        }

        m = MODULE_NOT_FOUND.matcher(output);
        if (m.find()) {
            hints.append("\n[hint: pip install ")
                 .append(m.group(1))
                 .append(" or uv pip install ")
                 .append(m.group(1))
                 .append("]");
        }

        if (PERMISSION_DENIED.matcher(output).find()) {
            hints.append("\n[hint: check file permissions or use sudo]");
        }

        if (NO_SUCH_FILE.matcher(output).find()) {
            hints.append("\n[hint: check file path exists]");
        }

        if (CONNECTION_REFUSED.matcher(output).find()) {
            hints.append("\n[hint: check if service is running and port is correct]");
        }

        return hints.length() > 0 ? hints.toString() : null;
    }

    // ── Error indicator detection (h48) ──────────────────────────────────

    static boolean containsErrorIndicators(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        for (Pattern p : ERROR_INDICATORS) {
            if (p.matcher(output).find()) {
                return true;
            }
        }
        return false;
    }

    // ── Signal name lookup (h47) ─────────────────────────────────────────

    static String signalName(int signal) {
        return SIGNALS.getOrDefault(signal, "SIGNAL_" + signal);
    }
}