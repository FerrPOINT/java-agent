package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRedactorTest {

    private AgentProperties props(boolean enabled, List<String> patterns, List<String> envPatterns) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setRedactEnabled(enabled);
        p.getSecurity().setSecretPatterns(patterns != null ? patterns : List.of());
        if (envPatterns != null) {
            p.getSecurity().getSensitiveEnvVarPatterns().addAll(envPatterns);
        }
        return p;
    }

    @Test
    void returnsNullWhenDisabled() {
        DefaultRedactor r = new DefaultRedactor(props(false, null, null));
        assertThat(r.redact("secret=abc")).isEqualTo("secret=abc");
    }

    @Test
    void redactsByRegex() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of("secret=[a-z]+"), null));
        assertThat(r.redact("secret=abc")).isEqualTo("[REDACTED]");
    }

    @Test
    void ignoresInvalidRegex() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of("[invalid"), null));
        assertThat(r.redact("abc")).isEqualTo("abc");
    }

    @Test
    void redactsEnvVar() {
        DefaultRedactor r = new DefaultRedactor(props(true, null, List.of("API_KEY")));
        assertThat(r.redactEnvVars("export API_KEY=12345\nOTHER=ok")).isEqualTo("export API_KEY=[REDACTED]\nOTHER=ok");
    }

    @Test
    void wildcardMatchesEnvVar() {
        DefaultRedactor r = new DefaultRedactor(props(true, null, List.of("*TOKEN*")));
        assertThat(r.redactEnvVars("MY_TOKEN=abc")).isEqualTo("MY_TOKEN=[REDACTED]");
    }
}
