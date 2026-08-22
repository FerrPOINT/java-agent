package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Hermes parity: /refine request — run the memory/skill background review
 * on demand with optional focus instructions (background_review.py focus).
 */
public record RefineRequest(
    @NotNull UUID sessionId,
    /** Optional steering text appended to the review prompt. */
    String focus
) {}
