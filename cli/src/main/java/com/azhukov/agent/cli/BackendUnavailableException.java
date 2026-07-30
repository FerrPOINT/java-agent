package com.azhukov.agent.cli;

/**
 * Thrown when the backend is unreachable (connection refused, timeout, etc.).
 * <p>
 * The REPL catches this to show a friendly "backend unavailable" message
 * and offer retry, rather than a raw stack trace.
 */
public class BackendUnavailableException extends RuntimeException {

    private final String backendUrl;

    public BackendUnavailableException(String backendUrl, Throwable cause) {
        super("Backend unavailable at " + backendUrl + ": " + cause.getMessage(), cause);
        this.backendUrl = backendUrl;
    }

    public String getBackendUrl() {
        return backendUrl;
    }
}