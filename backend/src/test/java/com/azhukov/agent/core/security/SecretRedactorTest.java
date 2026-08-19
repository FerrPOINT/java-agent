package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @Test
    void redactsApiKey() {
        AgentProperties p = new AgentProperties();
        SecretRedactor r = new SecretRedactor(p);
        String result = r.redact("api_key=supersecretvalue123 and done");
        assertThat(result).contains("[REDACTED:api_key]").doesNotContain("supersecretvalue123");
    }

    @Test
    void redactsOpenAiSkKey() {
        AgentProperties p = new AgentProperties();
        SecretRedactor r = new SecretRedactor(p);
        String result = r.redact("sk-abcdefghijklmnopqrstuvwxyz123456");
        assertThat(result).contains("[REDACTED:sk-abcdefghijklmnopqrstuvwxyz123456]");
    }

    @Test
    void redactsGitHubToken() {
        AgentProperties p = new AgentProperties();
        SecretRedactor r = new SecretRedactor(p);
        String result = r.redact("ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(result).contains("[REDACTED:ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa]");
    }

    @Test
    void redactsUrlCredentials() {
        AgentProperties p = new AgentProperties();
        SecretRedactor r = new SecretRedactor(p);
        String result = r.redact("http://user:pass@example.com");
        assertThat(result).startsWith("[REDACTED:").contains("example.com");
    }

    @Test
    void returnsNullWhenDisabled() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setRedactEnabled(false);
        SecretRedactor r = new SecretRedactor(p);
        assertThat(r.redact("api_key=secret")).isEqualTo("api_key=secret");
    }

    @Test
    void customPatternIsCompiledAndApplied() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setSecretPatterns(List.of("(?i)(mysecret)\\s*:\\s*([a-z0-9]+)"));
        SecretRedactor r = new SecretRedactor(p);
        String result = r.redact("mysecret: abc123");
        assertThat(result).contains("[REDACTED:mysecret]").doesNotContain("abc123");
    }

    @Test
    void ignoresInvalidCustomPattern() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setSecretPatterns(List.of("[invalid"));
        SecretRedactor r = new SecretRedactor(p);
        assertThat(r.redact("text")).isEqualTo("text");
    }
}
