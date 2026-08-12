package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared ObjectMapper singleton for the CLI module.
 * ObjectMapper is thread-safe after configuration and should not be
 * recreated on every use (REM-11).
 */
public final class SharedObjectMapper {

    private static final ObjectMapper INSTANCE = createDefaultMapper();

    private SharedObjectMapper() {
    }

    private static ObjectMapper createDefaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
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