package com.azhukov.agent.security;

public interface UrlSafety {

    boolean isUrlAllowed(String url);

    boolean isHostBlocked(String host);

    /**
     * Returns a human-readable reason if the URL is blocked, or null if allowed.
     * Provides structured metadata for logging and error responses.
     */
    default String checkUrl(String url) {
        if (url == null || url.isBlank()) {
            return "URL is empty";
        }
        return isUrlAllowed(url) ? null : "URL blocked by safety policy";
    }
}