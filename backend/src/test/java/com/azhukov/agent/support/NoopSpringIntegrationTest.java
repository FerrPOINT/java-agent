package com.azhukov.agent.support;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Shared application context for lightweight backend integration contracts.
 * Keep the configuration here stable so Spring can reuse one context across
 * test classes; tests that exercise different runtime profiles or databases
 * should use their own explicit base instead.
 */
@AutoConfigureMockMvc
@ActiveProfiles("noop")
@TestPropertySource(properties = {
    "agent.model.provider=noop",
    "agent.model.model-name=noop",
    "agent.model.base-url=http://localhost:0",
    "agent.security.api-key=test-key-123"
})
public abstract class NoopSpringIntegrationTest extends BackendIntegrationTestBase {
}
