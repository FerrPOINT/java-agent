package com.azhukov.agent.bot.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared ObjectMapper singleton for the telegram-bot module.
 * ObjectMapper is thread-safe after configuration and should not be
 * recreated on every use (REM-11).
 */
public final class SharedObjectMapper {

    private static final ObjectMapper DEFAULT_INSTANCE = createDefaultMapper();
    private static final ObjectMapper PRETTY_INSTANCE = createPrettyMapper();

    private SharedObjectMapper() {
    }

    private static ObjectMapper createDefaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private static ObjectMapper createPrettyMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    /**
     * Returns the shared, thread-safe {@link ObjectMapper} instance.
     *
     * @return the singleton ObjectMapper
     */
    public static ObjectMapper get() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Returns a shared ObjectMapper with {@code INDENT_OUTPUT} enabled.
     *
     * @return the pretty-printing ObjectMapper
     */
    public static ObjectMapper pretty() {
        return PRETTY_INSTANCE;
    }
}