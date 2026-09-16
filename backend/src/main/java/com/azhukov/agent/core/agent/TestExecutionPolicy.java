package com.azhukov.agent.core.agent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classifies a user's testing request so terminal execution cannot silently
 * turn a generic request into a repository-wide integration run.
 */
public final class TestExecutionPolicy {

    public static final String METADATA_KEY = "test_execution_scope";

    private static final Pattern TEST_REQUEST = Pattern.compile(
        "(?iu)(?:\\btest(?:ing|s)?\\b|\\bverify\\b|\\bcheck\\s+tests?\\b|тестир|протестир|тесты|провер.*тест)"
    );
    private static final Pattern BROAD_REQUEST = Pattern.compile(
        "(?iu)(?:\\b(?:all|full|entire|complete)\\s+(?:test|tests|suite)\\b|\\b(?:integration|slowtest|livetest|testcontainers)\\b|"
            + "(?:все|полный|полностью)\\s+(?:тест|тесты|набор)|интеграц|тестконтейнер|"
            + "\\./gradlew\\s+(?::?[\\w-]+:)?test\\b)"
    );
    private static final Pattern FOCUSED_REQUEST = Pattern.compile(
        "(?iu)(?:--tests\\b|\\b[A-Za-z_$][A-Za-z0-9_$]*Test\\b|\\b(?:focused|targeted|unit)\\b|"
            + "(?:целев|точеч|конкретн|юнит))"
    );
    private static final Pattern TEST_COMMAND = Pattern.compile(
        "(?iu)(?:\\bgradlew\\b.*\\b(?:test|slowTest|liveTest|e2eTest)\\b|\\bmvn\\b.*\\btest\\b|"
            + "\\bpytest\\b|\\bnpm\\s+(?:run\\s+)?test\\b|\\b(?:pnpm|yarn|bun)\\s+(?:run\\s+)?test\\b|\\btestcontainers\\b)"
    );
    private static final Pattern BROAD_COMMAND = Pattern.compile(
        "(?iu)(?:\\b(?:slowTest|liveTest|e2eTest)\\b|\\btestcontainers\\b|"
            + "\\bgradlew\\b(?![^\\n]*\\s--tests\\b)[^\\n]*(?:\\b(?:test|check|slowTest|liveTest|e2eTest)\\b)|"
            + "\\bmvn\\b(?:(?!-Dtest=).)*\\btest\\b|"
            + "\\bpytest\\b(?![^\\n]*\\s[\\w./-]+(?:\\.py|::))|"
            + "\\b(?:npm|pnpm|yarn|bun)\\s+(?:run\\s+)?test\\b)"
    );

    private TestExecutionPolicy() {
    }

    public enum Scope {
        NONE,
        GENERIC,
        FOCUSED,
        BROAD
    }

    public static Scope classify(String userMessage) {
        String message = userMessage == null ? "" : userMessage.trim();
        if (message.isEmpty() || !TEST_REQUEST.matcher(message).find()) {
            return Scope.NONE;
        }
        if (BROAD_REQUEST.matcher(message).find()) {
            return Scope.BROAD;
        }
        if (FOCUSED_REQUEST.matcher(message).find()) {
            return Scope.FOCUSED;
        }
        return Scope.GENERIC;
    }

    public static Scope fromSessionMetadata(String value) {
        if (value == null || value.isBlank()) {
            return Scope.NONE;
        }
        try {
            return Scope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Scope.NONE;
        }
    }

    public static String terminalRefusal(String command, Scope scope) {
        if (scope == Scope.GENERIC) {
            return "The user requested testing without naming a workspace or target. Do not run shell, Gradle, "
                + "or integration tests. Explain that a concrete workspace/test target is required; do not guess one.";
        }
        if (!TEST_COMMAND.matcher(command == null ? "" : command).find()) {
            return null;
        }
        if (scope == Scope.FOCUSED && BROAD_COMMAND.matcher(command).find()) {
            return "The user requested focused testing. Broad suites, slowTest/liveTest/e2eTest, Testcontainers, "
                + "and unfiltered Gradle/Maven/npm test commands require an explicit full or integration-test request. "
                + "Inspect the affected module and run one targeted test (for Gradle: :<module>:test --tests '<ClassName>').";
        }
        return null;
    }
}
