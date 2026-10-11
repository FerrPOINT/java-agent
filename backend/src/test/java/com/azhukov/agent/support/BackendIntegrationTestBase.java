package com.azhukov.agent.support;

import com.azhukov.agent.support.BackendIntegrationTestBase;

import org.junit.jupiter.api.Tag;

/**
 * Common root for backend tests that start the Spring application.
 * Spring integration tests run in the dedicated slow lane and derive from this
 * class so their environment variants can share stable base configurations.
 */
@Tag("slow")
public abstract class BackendIntegrationTestBase {
}
