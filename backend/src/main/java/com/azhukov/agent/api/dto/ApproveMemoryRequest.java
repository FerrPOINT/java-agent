package com.azhukov.agent.api.dto;

import java.util.UUID;

public record ApproveMemoryRequest(
    String userId,
    UUID id
) {}