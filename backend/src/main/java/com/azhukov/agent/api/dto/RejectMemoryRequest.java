package com.azhukov.agent.api.dto;

import java.util.UUID;

public record RejectMemoryRequest(
    String userId,
    UUID id
) {}