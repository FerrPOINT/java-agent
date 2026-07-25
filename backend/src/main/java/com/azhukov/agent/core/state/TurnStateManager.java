package com.azhukov.agent.core.state;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

public class TurnStateManager {

    private final ConcurrentMap<String, TurnState> states = new ConcurrentHashMap<>();

    public TurnState getOrStart(UUID sessionId, int turnIndex) {
        return states.computeIfAbsent(key(sessionId, turnIndex), k -> new TurnState(sessionId.toString(), turnIndex));
    }

    public TurnState get(UUID sessionId, int turnIndex) {
        return states.get(key(sessionId, turnIndex));
    }

    public void clear(UUID sessionId) {
        states.keySet().removeIf(k -> k.startsWith(sessionId.toString() + ":"));
    }

    private static String key(UUID sessionId, int turnIndex) {
        return sessionId + ":" + turnIndex;
    }
}
