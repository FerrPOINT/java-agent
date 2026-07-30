package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for {@link DefaultRedactor}.
 *
 * <p>Current implementation only has:
 * <ul>
 *   <li>An env-var pattern: {@code \b([A-Z_][A-Z0-9_]*)=(\S+)}</li>
 *   <li>Configurable regex patterns from {@code secretPatterns}</li>
 *   <li>Configurable sensitive env var name patterns (exact match or glob)</li>
 * </ul>
 *
 * <p>It does NOT have built-in patterns for:
 * <ul>
 *   <li>API keys (sk-, ghp_, xoxb-, AIza...)</li>
 *   <li>JWT tokens (eyJ...)</li>
 *   <li>Private key blocks (-----BEGIN ... PRIVATE KEY-----)</li>
 *   <li>Database connection strings with credentials</li>
 *   <li>Telegram bot tokens</li>
 *   <li>URLs with embedded credentials</li>
 *   <li>Authorization headers</li>
 * </ul>
 * Tests below verify current behavior and document gaps via test names.
 */
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

    // ─── Existing tests (preserved) ───

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
        assertThat(r.redactEnvVars("export API_KEY=12345\nOTHER=ok"))
                .isEqualTo("export API_KEY=[REDACTED]\nOTHER=ok");
    }

    @Test
    void wildcardMatchesEnvVar() {
        DefaultRedactor r = new DefaultRedactor(props(true, null, List.of("*TOKEN*")));
        assertThat(r.redactEnvVars("MY_TOKEN=abc")).isEqualTo("MY_TOKEN=[REDACTED]");
    }

    // ─── API key tests (GAP: no built-in API key patterns) ───

    @Test
    void openAiApiKey_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "The API key is sk-proj-abc123XYZdef456GHIjkl789MNO";
        assertThat(r.redact(text)).isEqualTo(text);
    }

    @Test
    void githubToken_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "Token: ghp_1234567890abcdefghijklmnopqrstuvwxyz";
        assertThat(r.redact(text)).isEqualTo(text);
    }

    @Test
    void slackToken_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "xoxb-1234567890-9876543210-abcdefghij";
        assertThat(r.redact(text)).isEqualTo(text);
    }

    @Test
    void googleApiKey_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "Key: AIzaSyD-abcdefghIJKLMnopqrst123UVWxyz";
        assertThat(r.redact(text)).isEqualTo(text);
    }

    @Test
    void openAiApiKey_redactedWhenPatternConfigured() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of("sk-[A-Za-z0-9]+"), null));
        String text = "The API key is sk-proj-abc123XYZdef456";
        assertThat(r.redact(text)).contains("[REDACTED]");
        assertThat(r.redact(text)).doesNotContain("sk-proj-abc123XYZdef456");
    }

    // ─── JWT token tests (GAP: no JWT pattern) ───

    @Test
    void jwtToken_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        assertThat(r.redact(jwt)).isEqualTo(jwt);
    }

    @Test
    void jwtToken_redactedWhenPatternConfigured() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), null));
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        assertThat(r.redact(jwt)).contains("[REDACTED]");
    }

    // ─── Private key block tests (GAP: no PEM detection) ───

    @Test
    void rsaPrivateKeyBlock_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String key = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIKBxQKBgQDM5c...\n-----END RSA PRIVATE KEY-----";
        assertThat(r.redact(key)).isEqualTo(key);
    }

    @Test
    void ecPrivateKeyBlock_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String key = "-----BEGIN EC PRIVATE KEY-----\nMHQCAQEE...\n-----END EC PRIVATE KEY-----";
        assertThat(r.redact(key)).isEqualTo(key);
    }

    @Test
    void openSshPrivateKeyBlock_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String key = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNz...\n-----END OPENSSH PRIVATE KEY-----";
        assertThat(r.redact(key)).isEqualTo(key);
    }

    @Test
    void privateKeyBlock_redactedWhenPatternConfigured() {
        DefaultRedactor r = new DefaultRedactor(props(true,
                List.of("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"), null));
        String key = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIKBxQKBgQDM5c...\n-----END RSA PRIVATE KEY-----";
        assertThat(r.redact(key)).contains("[REDACTED]");
    }

    // ─── Database connection string tests (GAP: no DB connection string pattern) ───

    @Test
    void postgresConnectionString_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String conn = "postgres://user:secretpass@host:5432/dbname";
        assertThat(r.redact(conn)).isEqualTo(conn);
    }

    @Test
    void mysqlConnectionString_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String conn = "mysql://admin:password123@10.0.0.1:3306/mydb";
        assertThat(r.redact(conn)).isEqualTo(conn);
    }

    @Test
    void mongodbConnectionString_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String conn = "mongodb://root:pass@cluster.example.com:27017/db?ssl=true";
        assertThat(r.redact(conn)).isEqualTo(conn);
    }

    @Test
    void postgresConnectionString_redactedWhenPatternConfigured() {
        DefaultRedactor r = new DefaultRedactor(props(true,
                List.of("(postgres|mysql|mongodb)://[^:]+:[^@]+@"), null));
        String conn = "postgres://user:secretpass@host:5432/dbname";
        assertThat(r.redact(conn)).contains("[REDACTED]");
    }

    // ─── Telegram bot token tests (GAP: no Telegram token pattern) ───

    @Test
    void telegramBotToken_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String token = "123456789:AAExH5abcDEFghiJKLmnoPQRstuVWXyz";
        assertThat(r.redact(token)).isEqualTo(token);
    }

    // ─── URL with credentials tests (GAP: no URL credential stripping) ───

    @Test
    void urlWithCredentials_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String url = "https://admin:password@internal.example.com/api/secret";
        assertThat(r.redact(url)).isEqualTo(url);
    }

    @Test
    void urlWithCredentials_redactedWhenPatternConfigured() {
        DefaultRedactor r = new DefaultRedactor(props(true,
                List.of("https://[^:]+:[^@]+@"), null));
        String url = "https://admin:password@internal.example.com/api/secret";
        assertThat(r.redact(url)).contains("[REDACTED]");
    }

    // ─── Authorization header tests (GAP: no auth header pattern) ───

    @Test
    void authorizationBearerHeader_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String header = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature";
        assertThat(r.redact(header)).isEqualTo(header);
    }

    @Test
    void authorizationBasicHeader_currentlyNotRedacted_noBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String header = "Authorization: Basic dXNlcjpwYXNzd29yZA==";
        assertThat(r.redact(header)).isEqualTo(header);
    }

    // ─── Multiple secrets in same text ───

    @Test
    void multipleSecrets_onlyEnvVarsRedacted_withoutConfiguredPatterns() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(),
                List.of("API_KEY", "*SECRET*", "TOKEN")));
        String text = "API_KEY=sk-abc123\n" +
                "MY_SECRET=ghp_xyz789\n" +
                "TOKEN=xoxb-aaa-bbb\n" +
                "Raw key: sk-proj-123456\n" +
                "JWT: eyJhbGciOiJIUzI1NiJ9.payload.sig\n" +
                "NORMAL_VAR=visible";
        String result = r.redact(text);

        // Env vars matching patterns are redacted
        assertThat(result).contains("API_KEY=[REDACTED]");
        assertThat(result).contains("MY_SECRET=[REDACTED]");
        assertThat(result).contains("TOKEN=[REDACTED]");
        // Raw secrets NOT in env var format are NOT redacted (GAP)
        assertThat(result).contains("sk-proj-123456");
        assertThat(result).contains("eyJhbGciOiJIUzI1NiJ9.payload.sig");
        // Non-sensitive env var is preserved
        assertThat(result).contains("NORMAL_VAR=visible");
    }

    @Test
    void multipleSecrets_allRedactedWithConfiguredPatterns() {
        DefaultRedactor r = new DefaultRedactor(props(true,
                List.of("sk-[A-Za-z0-9-]+", "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
                List.of("API_KEY")));
        String text = "API_KEY=sk-abc123\nRaw: sk-proj-123456\nJWT: eyJhbGciOiJIUzI1NiJ9.payload.sig";
        String result = r.redact(text);

        assertThat(result).contains("API_KEY=[REDACTED]");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("sk-proj-123456");
        assertThat(result).doesNotContain("eyJhbGciOiJIUzI1NiJ9.payload.sig");
    }

    // ─── Edge cases ───

    @Test
    void emptyString_returnsEmptyString() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        assertThat(r.redact("")).isEqualTo("");
    }

    @Test
    void nullInput_returnsNull() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        assertThat(r.redact(null)).isNull();
    }

    @Test
    void nullInputEnvVars_returnsNull() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        assertThat(r.redactEnvVars(null)).isNull();
    }

    @Test
    void disabledWithNullInput_returnsNull() {
        DefaultRedactor r = new DefaultRedactor(props(false, null, null));
        assertThat(r.redact(null)).isNull();
    }

    // ─── Env var pattern edge cases ───

    @Test
    void envVar_lowercaseName_notMatched() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("api_key")));
        // Pattern only matches uppercase: [A-Z_][A-Z0-9_]*
        String text = "api_key=secret123";
        assertThat(r.redactEnvVars(text)).isEqualTo(text);
    }

    @Test
    void envVar_mixedCaseName_notMatched() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("ApiKey")));
        String text = "ApiKey=secret123";
        // The regex requires [A-Z_] start — 'A' is uppercase so it matches
        // But 'k' is lowercase, and [A-Z0-9_]* doesn't match lowercase
        // So "ApiKey" wouldn't match the regex pattern \b([A-Z_][A-Z0-9_]*)=
        assertThat(r.redactEnvVars(text)).isEqualTo(text);
    }

    @Test
    void envVar_withSpacesInValue_onlyFirstTokenRedacted() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("SECRET")));
        // \S+ matches non-whitespace, so only first token after = is captured
        String text = "SECRET=abc123 rest of text";
        String result = r.redactEnvVars(text);
        assertThat(result).contains("SECRET=[REDACTED]");
        assertThat(result).contains("rest of text");
    }

    @Test
    void envVar_noMatch_noModification() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("SECRET")));
        String text = "no env vars here";
        assertThat(r.redactEnvVars(text)).isEqualTo(text);
    }

    @Test
    void envVar_emptySensitivePatterns_returnsUnchanged() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "API_KEY=secret123";
        assertThat(r.redactEnvVars(text)).isEqualTo(text);
    }

    // ─── Glob/wildcard matching ───

    @Test
    void wildcardAtStart_matchesEnvVar() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("*KEY")));
        assertThat(r.redactEnvVars("MY_KEY=secret")).isEqualTo("MY_KEY=[REDACTED]");
    }

    @Test
    void wildcardAtEnd_matchesEnvVar() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("API*")));
        assertThat(r.redactEnvVars("API_TOKEN=secret")).isEqualTo("API_TOKEN=[REDACTED]");
    }

    @Test
    void wildcardBothEnds_matchesEnvVar() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("*PASS*")));
        assertThat(r.redactEnvVars("MY_PASSWORD=secret")).isEqualTo("MY_PASSWORD=[REDACTED]");
        assertThat(r.redactEnvVars("PASSPHRASE=secret")).isEqualTo("PASSPHRASE=[REDACTED]");
    }

    @Test
    void exactMatch_envVar_notAffectedByGlob() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("TOKEN")));
        // Exact match — "TOKEN" matches "TOKEN" exactly
        assertThat(r.redactEnvVars("TOKEN=abc")).isEqualTo("TOKEN=[REDACTED]");
        // But "MY_TOKEN" should NOT match exact "TOKEN" (only glob *TOKEN* would)
        assertThat(r.redactEnvVars("MY_TOKEN=abc")).isEqualTo("MY_TOKEN=abc");
    }

    // ─── Multiple regex patterns ───

    @Test
    void multipleRegexPatterns_allApplied() {
        DefaultRedactor r = new DefaultRedactor(props(true,
                List.of("sk-[A-Za-z0-9]+", "ghp_[A-Za-z0-9]+"), null));
        String text = "keys: sk-abc123 and ghp_xyz789";
        String result = r.redact(text);
        assertThat(result).doesNotContain("sk-abc123");
        assertThat(result).doesNotContain("ghp_xyz789");
        assertThat(result).contains("[REDACTED]");
    }

    @Test
    void regexPattern_withReplacementTarget_noDataLeak() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of("password=\\S+"), null));
        String text = "password=hunter2";
        assertThat(r.redact(text)).isEqualTo("[REDACTED]");
        assertThat(r.redact(text)).doesNotContain("hunter2");
    }

    // ─── Disabled flag behavior ───

    @Test
    void disabled_returnsInputUnchanged_evenWithSecrets() {
        DefaultRedactor r = new DefaultRedactor(props(false, null, null));
        String text = "API_KEY=sk-secret123\npassword=hunter2";
        assertThat(r.redact(text)).isEqualTo(text);
    }

    @Test
    void redactEnvVars_worksRegardlessOfRedactEnabledFlag() {
        // redactEnvVars does NOT check isRedactEnabled — it always applies
        DefaultRedactor r = new DefaultRedactor(props(false, null, List.of("SECRET")));
        // GAP: redactEnvVars doesn't check the enabled flag, unlike redact()
        String text = "SECRET=value123";
        assertThat(r.redactEnvVars(text)).isEqualTo("SECRET=[REDACTED]");
    }

    // ─── Real-world scenario tests ───

    @Test
    void mixedEnvAndRawSecrets_envVarsRedacted_rawNotRedacted() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(),
                List.of("API_KEY", "*TOKEN*", "*PASSWORD*", "*SECRET*")));
        String text = "Configuring the agent:\n" +
                "API_KEY=sk-abc123\n" +
                "DB_PASSWORD=hunter2\n" +
                "AUTH_TOKEN=ghp_xyz789\n" +
                "MY_SECRET=xoxb-aaa-bbb\n" +
                "\n" +
                "Also found in logs:\n" +
                "sk-proj-raw-key-here\n" +
                "eyJhbGciOiJIUzI1NiJ9.body.sig\n" +
                "postgres://admin:pass@db:5432/myapp";
        String result = r.redact(text);

        // Env vars are redacted
        assertThat(result).contains("API_KEY=[REDACTED]");
        assertThat(result).contains("DB_PASSWORD=[REDACTED]");
        assertThat(result).contains("AUTH_TOKEN=[REDACTED]");
        assertThat(result).contains("MY_SECRET=[REDACTED]");

        // Raw secrets are NOT redacted (GAP)
        assertThat(result).contains("sk-proj-raw-key-here");
        assertThat(result).contains("eyJhbGciOiJIUzI1NiJ9.body.sig");
        assertThat(result).contains("postgres://admin:pass@db:5432/myapp");
    }
}