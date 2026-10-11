package com.azhukov.agent.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Common root for backend tests that start the Spring application.
 * Spring integration tests run in the dedicated slow lane and derive from this
 * class so their environment variants can share stable base configurations.
 */
@Tag("slow")
@SpringBootTest
public abstract class BackendIntegrationTestBase {
}
