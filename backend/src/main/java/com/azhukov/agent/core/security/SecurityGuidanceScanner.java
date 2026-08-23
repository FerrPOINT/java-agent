package com.azhukov.agent.core.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Feature 2: Security-guidance scanner — scans write_file/patch content for
 * dangerous code patterns and appends non-blocking warnings.
 *
 * Mirrors Hermes plugins/security-guidance/patterns.py.
 * Non-blocking: warnings are appended to tool output, the write is not prevented.
 */
@Slf4j
@Component
public class SecurityGuidanceScanner {

    /**
     * A single security pattern definition.
     */
    public record SecurityPattern(
        String ruleName,
        Pattern regex,
        List<String> substrings,
        String reminder
    ) {}

    private final List<SecurityPattern> patterns = new ArrayList<>();

    public SecurityGuidanceScanner() {
        initPatterns();
    }

    private void initPatterns() {
        // eval() — command injection
        patterns.add(new SecurityPattern(
            "eval_injection",
            Pattern.compile("(?<![a-zA-Z0-9_.])eval\\("),
            List.of(),
            "⚠️ Security Warning: eval() executes arbitrary code. Use JSON.parse() for data, ast.literal_eval() for Python literals."
        ));

        // pickle.load / pickle.loads — unsafe deserialization
        patterns.add(new SecurityPattern(
            "pickle_deserialization",
            Pattern.compile("(?<![a-zA-Z0-9_])pickle\\.(loads?|Unpickler)\\b"),
            List.of("pickle.load(", "pickle.loads(", "pickle.Unpickler("),
            "⚠️ Security Warning: Loading pickle from untrusted sources allows arbitrary code execution. Prefer JSON."
        ));

        // subprocess with shell=True — command injection
        patterns.add(new SecurityPattern(
            "python_subprocess_shell",
            Pattern.compile("subprocess\\.(?:run|call|Popen|check_output|check_call)\\(.*shell\\s*=\\s*True"),
            List.of(),
            "⚠️ Security Warning: subprocess with shell=True enables command injection. Pass arguments as a list without shell."
        ));

        // verify=False — TLS verification disabled
        patterns.add(new SecurityPattern(
            "tls_verification_disabled",
            Pattern.compile("\\bverify\\s*=\\s*False\\b|rejectUnauthorized\\s*:\\s*false|InsecureSkipVerify\\s*:\\s*true"),
            List.of("verify=False", "verify = False"),
            "⚠️ Security Warning: Don't disable TLS verification. This allows MITM attacks."
        ));

        // exec( — code injection
        patterns.add(new SecurityPattern(
            "exec_injection",
            Pattern.compile("(?<![a-zA-Z0-9_])exec\\("),
            List.of("exec("),
            "⚠️ Security Warning: exec() executes arbitrary code and is a major security risk."
        ));

        // __import__ — dynamic import
        patterns.add(new SecurityPattern(
            "dynamic_import",
            Pattern.compile("__import__\\("),
            List.of("__import__("),
            "⚠️ Security Warning: __import__() can execute arbitrary code. Use importlib.import_module() with validated names."
        ));

        // os.system — command injection
        patterns.add(new SecurityPattern(
            "os_system_injection",
            Pattern.compile("\\bos\\.system\\s*\\("),
            List.of("os.system(", "from os import system"),
            "⚠️ Security Warning: os.system() runs a shell and is a command-injection sink. Use subprocess.run([...]) instead."
        ));

        // chmod 777 — overly permissive
        patterns.add(new SecurityPattern(
            "chmod_777",
            Pattern.compile("chmod\\s+777\\b"),
            List.of("chmod 777"),
            "⚠️ Security Warning: chmod 777 grants full permissions to all users. Use least-privilege permissions."
        ));

        // rm -rf — dangerous deletion
        patterns.add(new SecurityPattern(
            "rm_rf",
            Pattern.compile("rm\\s+-rf?\\s+(?:/(?:\\s|$)|/\\*|~)"),
            List.of("rm -rf /", "rm -rf ~", "rm -rf /*"),
            "⚠️ Security Warning: rm -rf with root or home directory is extremely dangerous."
        ));

        // curl|bash — pipe-to-shell
        patterns.add(new SecurityPattern(
            "curl_pipe_bash",
            Pattern.compile("(?:curl|wget)\\s+[^|]*\\|\\s*(?:bash|sh|zsh)\\b"),
            List.of("curl | bash", "curl | sh", "wget | bash", "wget | sh"),
            "⚠️ Security Warning: Piping curl/wget output to shell executes arbitrary remote code."
        ));

        // yaml.load without SafeLoader
        patterns.add(new SecurityPattern(
            "unsafe_yaml_load",
            Pattern.compile("\\byaml\\.load\\s*\\((?![^)\\n]{0,80}\\bSafe)"),
            List.of("yaml.load("),
            "⚠️ Security Warning: yaml.load() without SafeLoader executes arbitrary Python. Use yaml.safe_load()."
        ));

        // marshal.loads — unsafe deserialization
        patterns.add(new SecurityPattern(
            "marshal_loads",
            Pattern.compile("\\bmarshal\\.loads?\\s*\\("),
            List.of("marshal.load(", "marshal.loads("),
            "⚠️ Security Warning: marshal.loads() allows arbitrary code execution via untrusted data."
        ));

        // innerHTML — XSS
        patterns.add(new SecurityPattern(
            "innerhtml_xss",
            Pattern.compile("\\.innerHTML\\s*="),
            List.of(".innerHTML =", ".innerHTML="),
            "⚠️ Security Warning: Setting innerHTML with untrusted content can lead to XSS vulnerabilities. Use textContent."
        ));

        // document.write — XSS
        patterns.add(new SecurityPattern(
            "document_write_xss",
            Pattern.compile("document\\.write\\s*\\("),
            List.of("document.write("),
            "⚠️ Security Warning: document.write() can be exploited for XSS attacks."
        ));
    }

    /**
     * Scan content for dangerous patterns.
     *
     * @param content the file content to scan
     * @param filePath the file path (used for path-specific patterns)
     * @return list of warning messages (empty if no patterns matched)
     */
    public List<String> scan(String content, String filePath) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }

        List<String> warnings = new ArrayList<>();
        for (SecurityPattern pattern : patterns) {
            if (matches(content, pattern)) {
                warnings.add("[" + pattern.ruleName() + "] " + pattern.reminder());
                log.debug("Security pattern '{}' matched in file {}", pattern.ruleName(), filePath);
            }
        }
        return warnings;
    }

    /**
     * Scan content and return a formatted warning string to append to tool output.
     * Hermes-exact format (plugins/security-guidance/_format_warning_block):
     * "---" + "⚠️ Security guidance — N pattern(s) matched (names)" + reminders
     * + the false-positive disclaimer. Non-blocking — the write already happened.
     *
     * @param content the file content to scan
     * @param filePath the file path
     * @return warning text to append, or empty string if no warnings
     */
    public String scanAndFormat(String content, String filePath) {
        List<String> warnings = scan(content, filePath);
        if (warnings.isEmpty()) {
            return "";
        }
        List<String> ruleNames = new ArrayList<>();
        for (SecurityPattern p : patterns) {
            if (matches(content == null ? "" : content, p)) {
                ruleNames.add(p.ruleName());
            }
        }
        StringBuilder sb = new StringBuilder("\n\n---\n");
        sb.append("⚠️ Security guidance — ").append(warnings.size())
          .append(warnings.size() == 1 ? " pattern matched (" : " patterns matched (")
          .append(String.join(", ", ruleNames)).append(")\n\n");
        for (String w : warnings) {
            // warnings entries are "[ruleName] reminder" — strip the bracket prefix,
            // the rule names ride the header line (Hermes parity)
            int close = w.indexOf("] ");
            sb.append(close >= 0 ? w.substring(close + 2) : w).append("\n\n");
        }
        sb.append("Pattern matches can be false positives. If the construct is safe in this ")
          .append("context, briefly document why in a code comment and continue. Otherwise, ")
          .append("fix the code before moving on.");
        return sb.toString();
    }

    private boolean matches(String content, SecurityPattern pattern) {
        // Check substring matches first (fast path)
        for (String sub : pattern.substrings()) {
            if (content.contains(sub)) {
                return true;
            }
        }
        // Check regex
        if (pattern.regex() != null && pattern.regex().matcher(content).find()) {
            return true;
        }
        return false;
    }

    /**
     * Get all defined patterns (for testing/introspection).
     */
    public List<SecurityPattern> getPatterns() {
        return List.copyOf(patterns);
    }
}