package com.azhukov.agent.api.dto;

import java.util.UUID;

public record UndoRequest(UUID sessionId, Integer turns) {
    public int effectiveTurns() {
        return turns != null && turns > 0 ? turns : 1;
    }
}