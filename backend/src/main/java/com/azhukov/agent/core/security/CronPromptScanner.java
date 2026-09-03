package com.azhukov.agent.core.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Strict scanner for user-supplied cron prompts. Mirrors the Hermes cronjob
 * create/update tripwire for obvious prompt-injection and exfiltration payloads.
 */
public final class CronPromptScanner {

    private static final String SECRET_VAR_RE = "\\$\\{?\\w*(?:KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)\\w*}?";

    private static final Pattern GITHUB_API_AUTH_HEADER = Pattern.compile(
        "curl\\s+[^\\n;&|$`]*(?:-H|--header)\\s+[\"']Authorization:\\s*token\\s+"
            + SECRET_VAR_RE
            + "[\"']\\s+[\"']?https://api\\.github\\.com(?::\\d+)?(?:/|\\s|$|[\"'])[^\\s;&|$`]*",
        Pattern.CASE_INSENSITIVE);

    private static final List<ThreatPattern> THREAT_PATTERNS = List.of(
        threat("ignore\\s+(?:\\w+\\s+)*(?:previous|all|above|prior)\\s+(?:\\w+\\s+)*instructions",
            "prompt_injection"),
        threat("do\\s+not\\s+tell\\s+the\\s+user", "deception_hide"),
        threat("system\\s+prompt\\s+override", "sys_prompt_override"),
        threat("disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)", "disregard_rules"),
        threat("cat\\s+[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|id_rsa|id_ed25519|id_ecdsa)",
            "read_secrets"),
        threat("authorized_keys", "ssh_backdoor"),
        threat("/etc/sudoers|visudo", "sudoers_mod"),
        threat("rm\\s+-rf\\s+/", "destructive_root_rm"),
        threat("curl\\s+[^\\n]*https?://[^\\s\"'`]*" + SECRET_VAR_RE, "exfil_curl_url"),
        threat("wget\\s+[^\\n]*https?://[^\\s\"'`]*" + SECRET_VAR_RE, "exfil_wget_url"),
        threat("curl\\s+[^\\n]*(?:--data(?:-raw|-binary|-urlencode)?|-d|--form|-F)\\s+[^\\n]*"
            + SECRET_VAR_RE, "exfil_curl_data"),
        threat("wget\\s+[^\\n]*--post-(?:data|file)=[^\\n]*" + SECRET_VAR_RE, "exfil_wget_post"),
        threat("curl\\s+[^\\n]*(?:-H|--header)\\s+[\"']Authorization:\\s*(?:Bearer|token)\\s+"
            + SECRET_VAR_RE + "[\"']", "exfil_curl_auth_header")
    );

    private static final List<Character> INVISIBLE_CHARS = List.of(
        '\u200B', '\u200C', '\u2060', '\uFEFF',
        '\u2061', '\u2062', '\u2063', '\u2064',
        '\u2066', '\u2067', '\u2068', '\u2069'
    );

    private CronPromptScanner() {
    }

    public static String scan(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        String promptToScan = stripSafeConstructs(prompt);
        for (char invisible : INVISIBLE_CHARS) {
            if (promptToScan.indexOf(invisible) >= 0) {
                return "Blocked: prompt contains invisible unicode U+" + String.format("%04X", (int) invisible)
                    + " (possible injection).";
            }
        }
        for (ThreatPattern threat : THREAT_PATTERNS) {
            if (threat.pattern().matcher(promptToScan).find()) {
                return "Blocked: prompt matches threat pattern '" + threat.id()
                    + "'. Cron prompts must not contain injection or exfiltration payloads.";
            }
        }
        return "";
    }

    private static String stripSafeConstructs(String prompt) {
        return GITHUB_API_AUTH_HEADER.matcher(prompt).replaceAll("curl https://api.github.com/user");
    }

    private static ThreatPattern threat(String regex, String id) {
        return new ThreatPattern(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), id);
    }

    private record ThreatPattern(Pattern pattern, String id) {
    }
}
