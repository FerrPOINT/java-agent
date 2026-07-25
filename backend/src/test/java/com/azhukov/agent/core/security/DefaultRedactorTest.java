package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRedactorTest {

    private final AgentProperties properties = new AgentProperties();

    @Test
    void redactsApiKeyAndTokenPatterns() {
        AgentProperties.SecurityProperties sec = properties.getSecurity();
        sec.setSecretPatterns(List.of("(?i)(api[_-]?key|token|secret)\s*=\s*[^\s&]+"));
        DefaultRedactor redactor = new DefaultRedactor(properties);

        String input = "Calling service with api_key=sk-123...cdef and token=ghp_XXX123, secret=shhh";
        String out = redactor.redact(input);

        assertThat(out).doesNotContain("sk-123...cdef").doesNotContain("ghp_XXX123").doesNotContain("shhh");
        assertThat(out).contains("[REDACTED]");
    }
}
