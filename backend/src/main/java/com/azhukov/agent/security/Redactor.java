package com.azhukov.agent.security;

public interface Redactor {

    String redact(String output);

    String redactEnvVars(String output);

    /** Redact PII (emails, phone numbers, IP addresses, credit card numbers) from output. */
    default String redactPii(String output) {
        return output;
    }

    /** Redact sensitive query parameters from a URL (e.g., token=, key=, password=). */
    default String redactUrlQuery(String url) {
        return url;
    }

    /** Redact sensitive fields from form-encoded body (e.g., password=, secret=). */
    default String redactFormBody(String body) {
        return body;
    }

    /** Redact userinfo (credentials) from a URL. */
    default String redactUrlUserinfo(String url) {
        return url;
    }
}