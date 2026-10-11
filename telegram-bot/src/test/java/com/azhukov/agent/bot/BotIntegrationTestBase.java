package com.azhukov.agent.bot;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Shared Spring configuration for bot integration tests using the test profile. */
@SpringBootTest
@ActiveProfiles("test")
public abstract class BotIntegrationTestBase {
}
