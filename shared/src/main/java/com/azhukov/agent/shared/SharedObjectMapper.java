package com.azhukov.agent.shared;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared Jackson ObjectMapper factory for all modules (backend, telegram-bot,
 * cli). Single owner of the JSON conventions so the REST surfaces cannot drift:
 * <ul>
 *   <li>JavaTimeModule registered, dates NOT written as timestamps</li>
 *   <li>unknown properties ignored on read (tolerant clients)</li>
 *   <li>common annotations (@JsonProperty) respected</li>
 * </ul>
 *
 * <p>Replaces the three per-module copies that had already diverged
 * (backend wrote dates as ISO strings, bot/cli did not; only bot had a pretty
 * variant). Every module now uses this factory through its own Spring
 * configuration; the static getters remain for non-Spring call sites
 * (CLI session store, tests).
 */
public final class SharedObjectMapper {

    private static final ObjectMapper INSTANCE = createDefaultMapper();
    private static final ObjectMapper PRETTY_INSTANCE = createPrettyMapper();

    private SharedObjectMapper() {
    }

    /**
     * Default mapper: ISO-8601 dates, tolerant reads.
     */
    public static ObjectMapper get() {
        return INSTANCE;
    }

    /**
     * Mapper with {@code INDENT_OUTPUT} enabled (human-facing output).
     */
    public static ObjectMapper pretty() {
        return PRETTY_INSTANCE;
    }

    private static ObjectMapper createDefaultMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static ObjectMapper createPrettyMapper() {
        return createDefaultMapper()
            .copy()
            .enable(SerializationFeature.INDENT_OUTPUT);
    }
}
