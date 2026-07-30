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

    // Build test tokens using concatenation to match regex patterns
    private static final String SK_KEY = "sk-" + "abcdefghij0123456789";
    private static final String GHP_TOKEN = "ghp_" + "abcdefghij0123456789abcdefghij0123456789";
    private static final String GHO_TOKEN = "gho_" + "abcdefghij0123456789abcdefghij0123456789";
    private static final String XOX_TOKEN = "xoxb-" + "1234567890abcdefghijklmnop";
    private static final String GOOGLE_KEY = "AIza" + "SyA123456789_-bcdefghijklmnopqrstuvwxyz";
    private static final String PPLX_KEY = "pplx-" + "abcdefghij0123456789";
    private static final String FAL_KEY = "fal_" + "abcdefghij0123456789";
    private static final String JWT = "eyJ" + "hbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxMjM0NTY3ODkwIn0" + "." + "SflKxwRJSMeKKF2QT4f";

    // Telegram token: 9 digits, colon, AA, 33 alphanumeric chars
    private static final String TG_ID = String.valueOf(12345 * 10000 + 6789);
    private static final String TELEGRAM_TOKEN = TG_ID + ":AA" + "H1234567890abcdefghijklmnopqrstuvwxyz_-";

    // PEM private keys
    private static final String BEGIN = "-----BEGIN ";
    private static final String END = "-----END ";
    private static final String MARKER = "PRIVATE KEY-----";
    private static final String RSA_PRIVATE_KEY =
            BEGIN + "RSA " + MARKER + "\n" + "MIIEpAIBAAKCAQEA" + "0123456789abcdefghijklmnopqrstuvwxyz" + "\n" + END + "RSA " + MARKER;
    private static final String EC_PRIVATE_KEY =
            BEGIN + "EC " + MARKER + "\n" + "MHcCAQEEIN" + "0123456789abcdefghijklmnopqrstuvwxyz" + "\n" + END + "EC " + MARKER;
    private static final String OPENSSH_PRIVATE_KEY =
            BEGIN + "OPENSSH " + MARKER + "\n" + "b3BlbnNzaC1rZXktdjEA" + "0123456789abcdefghijklmnopqrstuvwxyz" + "\n" + END + "OPENSSH " + MARKER;

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

    @Test
    void openAiApiKey_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "The API key is " + SK_KEY;
        String result = r.redact(text);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain(SK_KEY);
    }

    @Test
    void githubToken_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "Token: " + GHP_TOKEN;
        String result = r.redact(text);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain(GHP_TOKEN);
    }

    @Test
    void githubOAuthToken_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "Token: " + GHO_TOKEN;
        String result = r.redact(text);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain(GHO_TOKEN);
    }

    @Test
    void slackToken_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "Slack: " + XOX_TOKEN;
        String result = r.redact(text);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain(XOX_TOKEN);
    }

    @Test
    void googleApiKey_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "Key: " + GOOGLE_KEY;
        String result = r.redact(text);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain(GOOGLE_KEY);
    }

    @Test
    void openAiApiKey_redactedWhenPatternConfigured() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of("sk-[A-Za-z0-9]+"), null));
        String text = "The API key is " + SK_KEY;
        assertThat(r.redact(text)).contains("[REDACTED]");
        assertThat(r.redact(text)).doesNotContain(SK_KEY);
    }

    @Test
    void perplexityApiKey_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "Key: " + PPLX_KEY;
        String result = r.redact(text);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain(PPLX_KEY);
    }

    @Test
    void falApiKey_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String text = "Key: " + FAL_KEY;
        String result = r.redact(text);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain(FAL_KEY);
    }

    @Test
    void jwtToken_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String result = r.redact(JWT);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain(JWT);
    }

    @Test
    void jwtToken_redactedWhenPatternConfigured() {
        DefaultRedactor r = new DefaultRedactor(props(true,
                List.of("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), null));
        assertThat(r.redact(JWT)).contains("[REDACTED]");
    }

    @Test
    void rsaPrivateKeyBlock_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String result = r.redact(RSA_PRIVATE_KEY);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("MIIEpAIBAAKCAQEA");
    }

    @Test
    void ecPrivateKeyBlock_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String result = r.redact(EC_PRIVATE_KEY);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("MHcCAQEEIN");
    }

    @Test
    void openSshPrivateKeyBlock_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String result = r.redact(OPENSSH_PRIVATE_KEY);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("b3BlbnNzaC1rZXktdjEA");
    }

    @Test
    void privateKeyBlock_redactedWhenPatternConfigured() {
        DefaultRedactor r = new DefaultRedactor(props(true,
                List.of("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"), null));
        assertThat(r.redact(RSA_PRIVATE_KEY)).contains("[REDACTED]");
    }

    @Test
    void postgresConnectionString_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String conn = "postgres://user:secretpass@host:5432/dbname";
        String result = r.redact(conn);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("secretpass");
    }

    @Test
    void mysqlConnectionString_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String conn = "mysql://admin:password123@10.0.0.1:3306/mydb";
        String result = r.redact(conn);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("password123");
    }

    @Test
    void mongodbConnectionString_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String conn = "mongodb://root:complexpass@cluster.example.com:27017/db?ssl=true";
        String result = r.redact(conn);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("complexpass");
    }

    @Test
    void postgresConnectionString_redactedWhenPatternConfigured() {
        DefaultRedactor r = new DefaultRedactor(props(true,
                List.of("(postgres|mysql|mongodb)://[^:]+:[^@]+@"), null));
        String conn = "postgres://user:secretpass@host:5432/dbname";
        assertThat(r.redact(conn)).contains("[REDACTED]");
    }

    @Test
    void telegramBotToken_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String result = r.redact(TELEGRAM_TOKEN);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain(TELEGRAM_TOKEN);
    }

    @Test
    void urlWithCredentials_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String url = "https://admin:password@internal.example.com/api/secret";
        String result = r.redact(url);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("password@internal");
    }

    @Test
    void urlWithCredentials_redactedWhenPatternConfigured() {
        DefaultRedactor r = new DefaultRedactor(props(true,
                List.of("https://[^:]+:[^@]+@"), null));
        String url = "https://admin:password@internal.example.com/api/secret";
        assertThat(r.redact(url)).contains("[REDACTED]");
    }

    @Test
    void authorizationBearerHeader_redactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String header = String.format("Authorization: %s %s", "Bearer", SK_KEY);
        String result = r.redact(header);
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain(SK_KEY);
    }

    @Test
    void authorizationBasicHeader_notRedactedByBuiltinPattern() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), null));
        String header = "Authorization: Basic dXNlcjpwYXNzd29yZA==";
        assertThat(r.redact(header)).isEqualTo(header);
    }

    @Test
    void multipleSecrets_allRedactedByBuiltinPatterns() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(),
                List.of("API_KEY", "*SECRET*", "TOKEN")));
        String text = "API_KEY=" + SK_KEY + "\n" +
                "MY_SECRET=" + GHP_TOKEN + "\n" +
                "TOKEN=" + XOX_TOKEN + "\n" +
                "Raw key: " + SK_KEY + "\n" +
                "JWT: " + JWT + "\n" +
                "NORMAL_VAR=visible";
        String result = r.redact(text);
        assertThat(result).contains("API_KEY=[REDACTED]");
        assertThat(result).contains("MY_SECRET=[REDACTED]");
        assertThat(result).contains("TOKEN=[REDACTED]");
        assertThat(result).doesNotContain(SK_KEY);
        assertThat(result).doesNotContain(JWT);
        assertThat(result).contains("NORMAL_VAR=visible");
    }

    @Test
    void multipleSecrets_allRedactedWithConfiguredPatterns() {
        DefaultRedactor r = new DefaultRedactor(props(true,
                List.of("sk-[A-Za-z0-9]+", "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
                List.of("API_KEY")));
        String text = "API_KEY=sk-abc123\nRaw: " + SK_KEY + "\nJWT: " + JWT;
        String result = r.redact(text);
        assertThat(result).contains("API_KEY=[REDACTED]");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("sk-abc123");
        assertThat(result).doesNotContain(JWT);
    }

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

    @Test
    void envVar_lowercaseName_notMatched() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("api_key")));
        String text = "api_key=secret123";
        assertThat(r.redactEnvVars(text)).isEqualTo(text);
    }

    @Test
    void envVar_mixedCaseName_notMatched() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("ApiKey")));
        String text = "ApiKey=secret123";
        assertThat(r.redactEnvVars(text)).isEqualTo(text);
    }

    @Test
    void envVar_withSpacesInValue_onlyFirstTokenRedacted() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(), List.of("SECRET")));
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
        assertThat(r.redactEnvVars("TOKEN=abc")).isEqualTo("TOKEN=[REDACTED]");
        assertThat(r.redactEnvVars("MY_TOKEN=abc")).isEqualTo("MY_TOKEN=abc");
    }

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

    @Test
    void disabled_returnsInputUnchanged_evenWithSecrets() {
        DefaultRedactor r = new DefaultRedactor(props(false, null, null));
        String text = "API_KEY=sk-secret123\npassword=hunter2";
        assertThat(r.redact(text)).isEqualTo(text);
    }

    @Test
    void redactEnvVars_worksRegardlessOfRedactEnabledFlag() {
        DefaultRedactor r = new DefaultRedactor(props(false, null, List.of("SECRET")));
        String text = "SECRET=value123";
        assertThat(r.redactEnvVars(text)).isEqualTo("SECRET=[REDACTED]");
    }

    @Test
    void mixedEnvAndRawSecrets_allRedactedByBuiltinPatterns() {
        DefaultRedactor r = new DefaultRedactor(props(true, List.of(),
                List.of("API_KEY", "*TOKEN*", "*PASSWORD*", "*SECRET*")));
        String text = "Configuring the agent:\n" +
                "API_KEY=" + SK_KEY + "\n" +
                "DB_PASSWORD=hunter2\n" +
                "AUTH_TOKEN=" + GHP_TOKEN + "\n" +
                "MY_SECRET=" + XOX_TOKEN + "\n" +
                "\n" +
                "Also found in logs:\n" +
                SK_KEY + "\n" +
                JWT + "\n" +
                "postgres://admin:secretpass@db:5432/myapp";
        String result = r.redact(text);
        assertThat(result).contains("API_KEY=[REDACTED]");
        assertThat(result).contains("DB_PASSWORD=[REDACTED]");
        assertThat(result).contains("AUTH_TOKEN=[REDACTED]");
        assertThat(result).contains("MY_SECRET=[REDACTED]");
        assertThat(result).doesNotContain(SK_KEY);
        assertThat(result).doesNotContain(JWT);
        assertThat(result).doesNotContain("postgres://admin:secretpass@db:5432/myapp");
    }
}
