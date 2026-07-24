package com.azhukov.agent.gateway.model;

public record SessionSource(Platform platform, String chatId, String userId, String username, String displayName) {}
