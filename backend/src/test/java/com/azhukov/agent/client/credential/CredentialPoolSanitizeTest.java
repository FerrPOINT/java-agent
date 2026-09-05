package com.azhukov.agent.client.credential;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** M35 regression: provider error text is sanitized before persisting on a credential. */
class CredentialPoolSanitizeTest {

    private static String sanitize(String input) throws Exception {
        Method m = CredentialPool.class.getDeclaredMethod("sanitizeErrorMessage", String.class);
        m.setAccessible(true);
        CredentialPool pool = new CredentialPool("openai", List.of(), null);
        return (String) m.invoke(pool, input);
    }

    @Test
    void redactsBearerTokensAndApiKeys() throws Exception {
        String in = "Request failed: bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sig part, key sk-abcdef0123456789abcdef0123456789 rejected";
        String out = sanitize(in);
        assertThat(out).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        assertThat(out).doesNotContain("sk-abcdef0123456789abcdef0123456789");
        assertThat(out).contains("[REDACTED]");
    }

    @Test
    void keepsPlainMessagesIntact() throws Exception {
        String out = sanitize("Rate limit exceeded (429), retry after 30s");
        assertThat(out).isEqualTo("Rate limit exceeded (429), retry after 30s");
    }

    @Test
    void truncatesVeryLongMessages() throws Exception {
        String out = sanitize("x".repeat(2000));
        assertThat(out.length()).isLessThanOrEqualTo(501);
    }
}
