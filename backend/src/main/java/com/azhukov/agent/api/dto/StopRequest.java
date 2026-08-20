package com.azhukov.agent.api.dto;

import java.util.UUID;

/** m21: moved from AgentChatController inline record. */
public record StopRequest(UUID sessionId) {}
