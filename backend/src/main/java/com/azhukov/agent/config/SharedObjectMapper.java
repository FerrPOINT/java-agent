package com.azhukov.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Backend facade over the shared Jackson mapper (h10). Kept as a thin
 * delegator so existing backend call sites and tests stay unchanged; the
 * JSON conventions live in one place now ({@code com.azhukov.agent.shared.SharedObjectMapper}).
 */
public final class SharedObjectMapper {

    private SharedObjectMapper() {
    }

    public static ObjectMapper get() {
        return com.azhukov.agent.shared.SharedObjectMapper.get();
    }

    public static ObjectMapper pretty() {
        return com.azhukov.agent.shared.SharedObjectMapper.pretty();
    }
}
