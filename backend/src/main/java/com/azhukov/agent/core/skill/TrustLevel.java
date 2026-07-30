package com.azhukov.agent.core.skill;

/**
 * S12: Trust levels for skills — used by the security scanner.
 */
public enum TrustLevel {
    BUILTIN,
    TRUSTED,
    COMMUNITY,
    AGENT_CREATED
}