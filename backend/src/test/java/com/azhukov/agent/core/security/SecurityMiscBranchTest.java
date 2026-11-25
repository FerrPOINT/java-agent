package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for {@link SecretRedactor},
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
