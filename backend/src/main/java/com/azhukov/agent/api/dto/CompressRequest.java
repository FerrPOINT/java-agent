package com.azhukov.agent.api.dto;

import java.util.UUID;

public record CompressRequest(UUID sessionId, String focus) {}