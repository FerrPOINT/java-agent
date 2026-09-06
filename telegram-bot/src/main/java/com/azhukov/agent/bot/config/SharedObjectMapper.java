package com.azhukov.agent.bot.config;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bot facade over the shared Jackson mapper (h10). Same JSON conventions as
 * backend and CLI — no more per-module drift.
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
