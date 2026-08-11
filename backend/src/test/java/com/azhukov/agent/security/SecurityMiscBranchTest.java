package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for {@link SecretRedactor}, {@link CommandApprovalManager},
 * {@link UserInputSanitizer}, and {@link SsrfSafeHttpClient}.
 */
class SecurityMiscBranchTest {

    // ── SecretRedactor ──

    @Test
    void redact_nullInput_returnsNull() {
        AgentProperties p = new AgentProperties();
        SecretRedactor r = new SecretRedactor(p);
        assertThat(r.redact(null)).isNull();
    }

    @Test
    void redact_emptyInput_returnsEmpty() {
        AgentProperties p = new AgentProperties();
        SecretRedactor r = new SecretRedactor(p);
        assertThat(r.redact("")).isEmpty();
    }

    @Test
    void redact_disabledRedactSecrets_doesNotRedact() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setRedactSecrets(false);
        SecretRedactor r = new SecretRedactor(p);
        assertThat(r.redact("api_key=supersecret")).isEqualTo("api_key=supersecret");
    }

    @Test
    void redact_redactEnabledButDisabled_doesNotRedact() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setRedactEnabled(false);
        SecretRedactor r = new SecretRedactor(p);
        assertThat(r.redact("api_key=supersecret")).isEqualTo("api_key=supersecret");
    }

    @Test
    void redact_nullSecurity_usesDefaults() {
        AgentProperties p = new AgentProperties();
        // Security is initialized by default — test with default enabled state
        SecretRedactor r = new SecretRedactor(p);
        // Should default to enabled and redact
        String result = r.redact("api_key=supersecretvalue123");
        assertThat(result).contains("[REDACTED:");
    }

    @Test
    void redact_nullSecurityCustomPatterns_doesNotAddCustom() {
        AgentProperties p = new AgentProperties();
        SecretRedactor r = new SecretRedactor(p);
        // Should not throw with default security
        assertThat(r.redact("hello")).isEqualTo("hello");
    }

    @Test
    void redact_redactsAuthorizationHeader() {
        AgentProperties p = new AgentProperties();
        SecretRedactor r = new SecretRedactor(p);
        String result = r.redact("Authorization: Bearer abc123def456");
        assertThat(result).contains("[REDACTED:");
    }

    @Test
    void redact_redactsPasswordInUrl() {
        AgentProperties p = new AgentProperties();
        SecretRedactor r = new SecretRedactor(p);
        String result = r.redact("http://admin:password123@host.com");
        assertThat(result).contains("[REDACTED:");
    }

    @Test
    void redact_multipleCustomPatterns() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setSecretPatterns(List.of(
            "(?i)(mysecret)\\s*:\\s*([a-z0-9]+)",
            "(?i)(customtoken)\\s*=\\s*([A-Z0-9]+)"
        ));
        SecretRedactor r = new SecretRedactor(p);
        assertThat(r.redact("mysecret: abc123")).contains("[REDACTED:");
        assertThat(r.redact("customtoken=ABC123")).contains("[REDACTED:");
    }

    @Test
    void redact_mixedValidAndInvalidCustomPatterns() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setSecretPatterns(List.of(
            "(?i)(mysecret)\\s*:\\s*([a-z0-9]+)",  // valid
            "[invalid",                              // invalid
            "(?i)(valid)\\s*=\\s*(\\d+)"            // valid
        ));
        SecretRedactor r = new SecretRedactor(p);
        assertThat(r.redact("mysecret: abc123")).contains("[REDACTED:");
        assertThat(r.redact("valid=123")).contains("[REDACTED:");
    }

    // ── CommandApprovalManager ──

    @Test
    void requireApproval_nullCommand_returnsYolo() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        CommandApprovalManager m = new CommandApprovalManager(p);
        assertThat(m.requireApproval(null)).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_blankCommand_returnsYolo() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        CommandApprovalManager m = new CommandApprovalManager(p);
        assertThat(m.requireApproval("  ")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_commandMatchingBlocked_isBlocked() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        p.getSecurity().setBlockedCommands(List.of("curl", "wget"));
        CommandApprovalManager m = new CommandApprovalManager(p);
        assertThat(m.requireApproval("curl http://example.com")).isEqualTo(CommandApprovalManager.ApprovalStatus.BLOCKED);
        assertThat(m.requireApproval("wget http://example.com")).isEqualTo(CommandApprovalManager.ApprovalStatus.BLOCKED);
    }

    @Test
    void requireApproval_commandMatchingSessionAllowlist_isAllowed() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        CommandApprovalManager m = new CommandApprovalManager(p);
        m.allowForSession("rm -rf /tmp/safe");
        assertThat(m.requireApproval("rm -rf /tmp/safe")).isEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
    }

    @Test
    void allowForSession_nullOrBlank_doesNotAdd() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        CommandApprovalManager m = new CommandApprovalManager(p);
        m.allowForSession(null);
        m.allowForSession("");
        m.allowForSession("  ");
        assertThat(m.requireApproval("some command")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_readOnlyCommand_isYolo() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        CommandApprovalManager m = new CommandApprovalManager(p);
        assertThat(m.requireApproval("ls")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
        assertThat(m.requireApproval("cat /etc/hosts")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
        assertThat(m.requireApproval("pwd")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
        assertThat(m.requireApproval("whoami")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
        assertThat(m.requireApproval("echo hello")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
        assertThat(m.requireApproval("grep pattern file")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
        assertThat(m.requireApproval("find . -name x")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
        assertThat(m.requireApproval("env")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_dangerousPatterns_requireApproval() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        CommandApprovalManager m = new CommandApprovalManager(p);
        assertThat(m.requireApproval("mkfs.ext4 /dev/sda")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
        assertThat(m.requireApproval("dd if=/dev/zero of=/dev/sda")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
        assertThat(m.requireApproval(":(){ :|: & };:")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
        assertThat(m.requireApproval("reboot")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
        assertThat(m.requireApproval("halt")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
        assertThat(m.requireApproval("init 0")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_nonReadOnlyCommand_isYolo() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        CommandApprovalManager m = new CommandApprovalManager(p);
        // Non-readonly, non-dangerous commands without approval-required config → YOLO
        assertThat(m.requireApproval("npm install")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
        assertThat(m.requireApproval("python script.py")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void clearSessionAllowlist_afterClear_removesEntries() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        CommandApprovalManager m = new CommandApprovalManager(p);
        m.allowForSession("rm -rf /tmp/test");
        assertThat(m.requireApproval("rm -rf /tmp/test")).isEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
        m.clearSessionAllowlist();
        assertThat(m.requireApproval("rm -rf /tmp/test")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    // ── UserInputSanitizer ──

    @Test
    void sanitize_normalizesUnicode() {
        UserInputSanitizer s = new UserInputSanitizer();
        // NFC normalization
        String result = s.sanitize("caf\u0065\u0301");
        assertThat(result).isEqualTo("café");
    }

    @Test
    void sanitize_exactMaxLength_isNotTruncated() {
        UserInputSanitizer s = new UserInputSanitizer();
        String exact = "x".repeat(200_000);
        String result = s.sanitize(exact);
        assertThat(result).isEqualTo(exact);
    }
}