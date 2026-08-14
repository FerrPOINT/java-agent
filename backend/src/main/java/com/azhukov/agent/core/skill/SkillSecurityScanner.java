package com.azhukov.agent.core.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Security scanner for skill content — ported from the original project's {@code skills_guard.py}.
 *
 * <p>Scans skill content (SKILL.md + supporting files) for known-bad patterns:
 * exfiltration, prompt injection, destructive operations, persistence, network
 * threats, obfuscation, supply-chain attacks, privilege escalation, and
 * credential exposure.
 *
 * <p>Each pattern has a severity ({@code critical}, {@code high}, {@code medium},
 * {@code low}) and a category. The verdict is determined by the highest-severity
 * finding:
 * <ul>
 * <li>{@code critical} → {@code dangerous}</li>
 * <li>{@code high} → {@code dangerous}</li>
 * <li>{@code medium} → {@code caution}</li>
 * <li>{@code low} → {@code safe}</li>
 * <li>no findings → {@code safe}</li>
 * </ul>
 *
 * <p>Trust-aware install policy (mirrors the original project's {@code INSTALL_POLICY}):
 * <pre>
 * safe caution dangerous
 * BUILTIN allow allow allow
 * TRUSTED allow allow block
 * COMMUNITY allow block block
 * AGENT_CREATED allow allow block
 * </pre>
 */
public final class SkillSecurityScanner {

 public enum Verdict { SAFE, CAUTION, DANGEROUS }

 /** Trust-aware install policy: [trustLevel][verdict] → allowed? */
 private static final boolean[][] INSTALL_POLICY = {
 // SAFE CAUTION DANGEROUS
 /* BUILTIN */ { true, true, true },
 /* TRUSTED */ { true, true, false },
 /* COMMUNITY */ { true, false, false },
 /* AGENT_CREATED */ { true, true, false },
 };

 // ─── Threat pattern definition ───

 public record ThreatPattern(
 Pattern regex,
 String patternId,
 String severity, // critical | high | medium | low
 String category, // exfiltration | injection | destructive | etc.
 String description
 ) {}

 /** A single finding produced by the scanner. */
 public record Finding(
 String patternId,
 String severity,
 String category,
 String file,
 int line,
 String match,
 String description
 ) {}

 /** Full scan result. */
 public record ScanResult(
 String skillName,
 String trustLevel,
 Verdict verdict,
 List<Finding> findings,
 String summary
 ) {}

 // ─── Built-in threat patterns (ported from skills_guard.py) ───

 private static final List<ThreatPattern> THREAT_PATTERNS = List.of(
 // ── Exfiltration: shell commands leaking secrets ──
 tp("curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)",
 "env_exfil_curl", "critical", "exfiltration",
 "curl command interpolating secret environment variable"),
 tp("wget\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)",
 "env_exfil_wget", "critical", "exfiltration",
 "wget command interpolating secret environment variable"),
 tp("fetch\\s*\\([^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|API)",
 "env_exfil_fetch", "critical", "exfiltration",
 "fetch() call interpolating secret environment variable"),

 // ── Exfiltration: reading credential stores ──
 tp("\\$HOME/\\.ssh|~/\\.ssh",
 "ssh_dir_access", "high", "exfiltration",
 "references user SSH directory"),
 tp("\\$HOME/\\.aws|~/\\.aws",
 "aws_dir_access", "high", "exfiltration",
 "references user AWS credentials directory"),
 tp("\\$HOME/\\.gnupg|~/\\.gnupg",
 "gpg_dir_access", "high", "exfiltration",
 "references user GPG keyring"),
 tp("\\$HOME/\\.kube|~/\\.kube",
 "kube_dir_access", "high", "exfiltration",
 "references Kubernetes config directory"),
 tp("\\$HOME/\\.docker|~/\\.docker",
 "docker_dir_access", "high", "exfiltration",
 "references Docker config (may contain registry creds)"),
 tp("cat\\s+(?!>)[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc)",
 "read_secrets_file", "critical", "exfiltration",
 "reads known secrets file"),

 // ── Exfiltration: env dump ──
 tp("printenv|env\\s*\\|",
 "dump_all_env", "high", "exfiltration",
 "dumps all environment variables"),

 // ── Exfiltration: DNS and staging ──
 tp("\\b(dig|nslookup|host)\\s+[^\\n]*\\$",
 "dns_exfil", "critical", "exfiltration",
 "DNS lookup with variable interpolation (possible DNS exfiltration)"),

 // ── Exfiltration: markdown/link based ──
 tp("!\\[.*\\]\\(https?://[^\\)]*\\$\\{?",
 "md_image_exfil", "high", "exfiltration",
 "markdown image URL with variable interpolation (image-based exfil)"),

 // ── Prompt injection ──
 tp("ignore\\s+(?:\\w+\\s+)*(previous|all|above|prior)\\s+instructions",
 "prompt_injection_ignore", "critical", "injection",
 "prompt injection: ignore previous instructions"),
 tp("you\\s+are\\s+(?:\\w+\\s+)*now\\s+",
 "role_hijack", "high", "injection",
 "attempts to override the agent's role"),
 tp("do\\s+not\\s+(?:\\w+\\s+)*tell\\s+(?:\\w+\\s+)*the\\s+user",
 "deception_hide", "critical", "injection",
 "instructs agent to hide information from user"),
 tp("system\\s+(?:\\w+\\s+)*prompt\\s+(?:\\w+\\s+)*override",
 "sys_prompt_override", "critical", "injection",
 "attempts to override the system prompt"),
 tp("pretend\\s+(?:\\w+\\s+)*(you\\s+are|to\\s+be)\\s+",
 "role_pretend", "high", "injection",
 "attempts to make the agent assume a different identity"),
 tp("disregard\\s+(?:\\w+\\s+)*(your|all|any)\\s+(?:\\w+\\s+)*(instructions|rules|guidelines)",
 "disregard_rules", "critical", "injection",
 "instructs agent to disregard its rules"),
 tp("output\\s+(?:\\w+\\s+)*(system|initial)\\s+prompt",
 "leak_system_prompt", "high", "injection",
 "attempts to extract the system prompt"),

 // ── Destructive operations ──
 tp(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;\\s*:",
 "fork_bomb", "critical", "destructive",
 "fork bomb pattern"),
 tp("rm\\s+-rf\\s+/",
 "destructive_root_rm", "critical", "destructive",
 "recursive delete from root"),
 tp("rm\\s+(-[^\\s]*)?r.*\\$HOME|\\brmdir\\s+.*\\$HOME",
 "destructive_home_rm", "critical", "destructive",
 "recursive delete targeting home directory"),
 tp("chmod\\s+777",
 "insecure_perms", "medium", "destructive",
 "sets world-writable permissions"),
 tp(">\\s*/etc/",
 "system_overwrite", "critical", "destructive",
 "overwrites system configuration file"),
 tp("\\bmkfs\\b",
 "format_filesystem", "critical", "destructive",
 "formats a filesystem"),
 tp("\\bdd\\s+.*if=.*of=/dev/",
 "disk_overwrite", "critical", "destructive",
 "raw disk write operation"),

 // ── Persistence ──
 tp("\\bcrontab\\b",
 "persistence_cron", "medium", "persistence",
 "modifies cron jobs"),
 tp("\\.(bashrc|zshrc|profile|bash_profile|bash_login|zprofile|zlogin)\\b",
 "shell_rc_mod", "medium", "persistence",
 "references shell startup file"),
 tp("authorized_keys",
 "ssh_backdoor", "critical", "persistence",
 "modifies SSH authorized keys"),
 tp("systemd.*\\.service|systemctl\\s+(enable|start)",
 "systemd_service", "medium", "persistence",
 "references or enables systemd service"),
 tp("/etc/sudoers|visudo",
 "sudoers_mod", "critical", "persistence",
 "modifies sudoers (privilege escalation)"),

 // ── Network: reverse shells and tunnels ──
 tp("\\bnc\\s+-[lp]|ncat\\s+-[lp]|\\bsocat\\b",
 "reverse_shell", "critical", "network",
 "potential reverse shell listener"),
 tp("\\bngrok\\b|\\blocaltunnel\\b|\\bserveo\\b|\\bcloudflared\\b",
 "tunnel_service", "high", "network",
 "uses tunneling service for external access"),
 tp("webhook\\.site|requestbin\\.com|pipedream\\.net|hookbin\\.com",
 "exfil_service", "high", "network",
 "references known data exfiltration/webhook testing service"),

 // ── Obfuscation: encoding and eval ──
 tp("base64\\s+(-d|--decode)\\s*\\|",
 "base64_decode_pipe", "high", "obfuscation",
 "base64 decodes and pipes to execution"),
 tp("\\beval\\s*\\(\\s*[\"']",
 "eval_string", "high", "obfuscation",
 "eval() with string argument"),
 tp("echo\\s+[^\\n]*\\|\\s*(bash|sh|python|perl|ruby|node)",
 "echo_pipe_exec", "critical", "obfuscation",
 "echo piped to interpreter for execution"),

 // ── Supply chain: curl/wget pipe to shell ──
 tp("curl\\s+[^|]*\\|\\s*(ba)?sh",
 "curl_pipe_shell", "critical", "supply_chain",
 "curl piped to shell (download-and-execute)"),
 tp("wget\\s+[^|]*-O\\s*-\\s*\\|\\s*(ba)?sh",
 "wget_pipe_shell", "critical", "supply_chain",
 "wget piped to shell (download-and-execute)"),
 tp("curl\\s+[^|]*\\|\\s*python",
 "curl_pipe_python", "critical", "supply_chain",
 "curl piped to Python interpreter"),

 // ── Privilege escalation ──
 tp("\\bsudo\\b",
 "sudo_usage", "high", "privilege_escalation",
 "uses sudo (privilege escalation)"),
 tp("setuid|setgid|cap_setuid",
 "setuid_setgid", "critical", "privilege_escalation",
 "setuid/setgid (privilege escalation mechanism)"),
 tp("NOPASSWD",
 "nopasswd_sudo", "critical", "privilege_escalation",
 "NOPASSWD sudoers entry (passwordless privilege escalation)"),

 // ── Agent config persistence ──
 tp("AGENTS\\.md|CLAUDE\\.md|\\.cursorrules|\\.clinerules",
 "agent_config_mod", "critical", "persistence",
 "references agent config files (could persist malicious instructions across sessions)"),

 // ── Hardcoded secrets ──
 tp("(?:api[_-]?key|token|secret|password)\\s*[=:]\\s*[\"'][A-Za-z0-9+/=_-]{20,}",
 "hardcoded_secret", "critical", "credential_exposure",
 "possible hardcoded API key, token, or secret"),
 tp("-----BEGIN\\s+(RSA\\s+)?PRIVATE\\s+KEY-----",
 "embedded_private_key", "critical", "credential_exposure",
 "embedded private key"),
 tp("ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{80,}",
 "github_token_leaked", "critical", "credential_exposure",
 "GitHub personal access token in skill content"),
 tp("sk-[A-Za-z0-9]{20,}",
 "openai_key_leaked", "critical", "credential_exposure",
 "possible OpenAI API key in skill content"),

 // ── Jailbreak patterns ──
 tp("\\bDAN\\s+mode\\b|Do\\s+Anything\\s+Now",
 "jailbreak_dan", "critical", "injection",
 "DAN (Do Anything Now) jailbreak attempt"),
 tp("for\\s+educational\\s+purposes?\\s+only",
 "educational_pretext", "medium", "injection",
 "educational pretext often used to justify harmful content"),

 // ── Context window exfiltration ──
 tp("(include|output|print|send|share)\\s+(?:\\w+\\s+)*(conversation|chat\\s+history|previous\\s+messages|context)",
 "context_exfil", "high", "exfiltration",
 "instructs agent to output/share conversation history"),
 tp("(send|post|upload|transmit)\\s+.*\\s+(to|at)\\s+https?://",
 "send_to_url", "high", "exfiltration",
 "instructs agent to send data to a URL")
 );

 private static ThreatPattern tp(String regex, String id, String severity, String category, String desc) {
 return new ThreatPattern(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), id, severity, category, desc);
 }

 private SkillSecurityScanner() {}

 /**
 * Scan a single string of skill content for threat patterns.
 *
 * @param content the skill content (e.g. SKILL.md body)
 * @param label file label for findings (e.g. "SKILL.md")
 * @return list of findings (may be empty)
 */
 public static List<Finding> scanContent(String content, String label) {
 if (content == null || content.isBlank()) {
 return List.of();
 }
 List<Finding> findings = new ArrayList<>();
 String[] lines = content.split("\n");
 // Track seen (patternId, line) for dedup
 var seen = new java.util.HashSet<String>();

 for (ThreatPattern tp : THREAT_PATTERNS) {
 for (int i = 0; i < lines.length; i++) {
 String key = tp.patternId() + ":" + (i + 1);
 if (seen.contains(key)) continue;
 if (tp.regex().matcher(lines[i]).find()) {
 seen.add(key);
 String match = lines[i].strip();
 if (match.length() > 120) match = match.substring(0, 117) + "...";
 findings.add(new Finding(
 tp.patternId(), tp.severity(), tp.category(),
 label, i + 1, match, tp.description()
 ));
 }
 }
 }
 return findings;
 }

 /**
 * Scan skill content and produce a full {@link ScanResult} with verdict.
 *
 * @param skillName name of the skill
 * @param content SKILL.md content
 * @param trustLevel trust level of the skill source
 * @return scan result with verdict and findings
 */
 public static ScanResult scan(String skillName, String content, TrustLevel trustLevel) {
 List<Finding> findings = scanContent(content, "SKILL.md");
 Verdict verdict = computeVerdict(findings);
 String summary = findings.isEmpty()
 ? "No security findings."
 : "Found " + findings.size() + " security finding(s): " +
 findings.stream().map(f -> f.patternId() + " (" + f.severity() + ")").reduce((a, b) -> a + ", " + b).orElse("");
 return new ScanResult(skillName, trustLevel.name(), verdict, findings, summary);
 }

 /**
 * Determine whether a scan result should be allowed based on trust level and verdict.
 *
 * @param result the scan result
 * @return {@code true} if the skill should be allowed, {@code false} if blocked
 */
 public static boolean shouldAllow(ScanResult result) {
 TrustLevel trust = TrustLevel.valueOf(result.trustLevel());
 Verdict verdict = result.verdict();
 return INSTALL_POLICY[trust.ordinal()][verdict.ordinal()];
 }

 /**
 * Return an error message if the skill content is blocked by the security scanner,
 * or {@code null} if the content is allowed.
 *
 * @param skillName name of the skill
 * @param content SKILL.md content
 * @param trustLevel trust level of the skill source
 * @return error message if blocked, {@code null} if allowed
 */
 public static String scanAndGuard(String skillName, String content, TrustLevel trustLevel) {
 ScanResult result = scan(skillName, content, trustLevel);
 if (!shouldAllow(result)) {
 return formatScanReport(result);
 }
 return null;
 }

 /**
 * Format a scan result as a human-readable report string.
 */
 public static String formatScanReport(ScanResult result) {
 StringBuilder sb = new StringBuilder();
 sb.append("Security scan blocked skill '").append(result.skillName()).append("' (trust: ")
 .append(result.trustLevel()).append(", verdict: ").append(result.verdict()).append(")\n");
 sb.append("Findings:\n");
 for (Finding f : result.findings()) {
 sb.append(" [").append(f.severity()).append("] ").append(f.patternId())
 .append(" at ").append(f.file()).append(":").append(f.line())
 .append(" — ").append(f.description()).append("\n");
 sb.append(" Match: ").append(f.match()).append("\n");
 }
 return sb.toString();
 }

 private static Verdict computeVerdict(List<Finding> findings) {
 if (findings.isEmpty()) return Verdict.SAFE;
 boolean hasCriticalOrHigh = false;
 boolean hasMedium = false;
 for (Finding f : findings) {
 if ("critical".equals(f.severity()) || "high".equals(f.severity())) {
 hasCriticalOrHigh = true;
 } else if ("medium".equals(f.severity())) {
 hasMedium = true;
 }
 }
 if (hasCriticalOrHigh) return Verdict.DANGEROUS;
 if (hasMedium) return Verdict.CAUTION;
 return Verdict.SAFE;
 }
}