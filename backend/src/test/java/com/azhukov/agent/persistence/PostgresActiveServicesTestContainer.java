package com.azhukov.agent.persistence;

import org.springframework.test.context.TestPropertySource;

/** PostgreSQL test environment for contracts that exercise memory and skills beans. */
@TestPropertySource(properties = {
    "agent.memory.enabled=true",
    "agent.skills.enabled=true"
})
public abstract class PostgresActiveServicesTestContainer extends PostgresTestContainer {
}
