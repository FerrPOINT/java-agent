package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * CLI facade over the shared Jackson mapper (h10). Dates are ISO-8601 and
 * unknown properties are ignored — same conventions as backend and bot.
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
