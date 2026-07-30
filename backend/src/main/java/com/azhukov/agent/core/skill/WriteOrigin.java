package com.azhukov.agent.core.skill;

/**
 * S6: Provenance — tracks where a skill write originated from.
 */
public enum WriteOrigin {
    FOREGROUND,
    BACKGROUND_REVIEW,
    CURATOR,
    HUB_INSTALL
}