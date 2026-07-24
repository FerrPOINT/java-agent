package com.azhukov.agent.gateway.model;

import java.util.Map;

public record PlatformConfig(Platform platform, boolean enabled, Map<String, String> secrets, Map<String, String> options) {}
