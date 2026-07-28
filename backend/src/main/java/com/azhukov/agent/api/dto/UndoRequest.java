package com.azhukov.agent.api.dto;

import java.util.UUID;

public record UndoRequest(UUID sessionId, int turns) {}