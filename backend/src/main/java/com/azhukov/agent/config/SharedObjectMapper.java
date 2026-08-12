package com.azhukov.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared ObjectMapper singleton for reuse across the backend.
 * ObjectMapper is thread-safe after configuration and should not be
 * recreated on every use (REM-11).
 */
public final class SharedObjectMapper {

    private static final ObjectMapper INSTANCE = new ObjectMapper();

    private SharedObjectMapper() {
    }

    /**
     * Returns the shared, thread-safe {@link ObjectMapper} instance.
     *
     * @return the singleton ObjectMapper
     */
    public static ObjectMapper get() {
        return INSTANCE;
    }
}