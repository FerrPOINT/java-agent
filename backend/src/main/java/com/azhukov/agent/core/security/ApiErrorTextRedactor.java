package com.azhukov.agent.core.security;

import java.util.regex.Pattern;

public final class ApiErrorTextRedactor {

    private static final Pattern API_KEY_TOKEN = Pattern.compile("sk-[A-Za-z0-9_-]{20,}");
    private static final Pattern SENSITIVE_ENV_ASSIGNMENT = Pattern.compile(
        "(?i)\\b([A-Z_][A-Z0-9_]*(?:API[_-]?KEY|SECRET|TOKEN|PASSWORD|PRIVATE[_-]?KEY|ACCESS[_-]?KEY)[A-Z0-9_]*)=([^\\s,;\"']+)"
    );

    private ApiErrorTextRedactor() {
    }

    public static String redacted(String value) {
        return redacted(value, null);
    }

    public static String redacted(String value, Redactor redactor) {
        if (value == null) {
            return null;
        }
        String safe = value;
        if (redactor != null) {
            String redacted = redactor.redact(value);
            if (redacted != null) {
                safe = redacted;
            }
        }
        safe = SENSITIVE_ENV_ASSIGNMENT.matcher(safe).replaceAll("$1=[REDACTED]");
        return API_KEY_TOKEN.matcher(safe).replaceAll("[REDACTED]");
    }
}
