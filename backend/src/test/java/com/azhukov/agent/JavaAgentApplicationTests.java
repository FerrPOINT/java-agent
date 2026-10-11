package com.azhukov.agent;

import com.azhukov.agent.support.BackendIntegrationTestBase;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
@Tag("slow")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    // This intentionally uses an empty H2 database. Keep Hibernate DDL enabled
    // so ApplicationReady lifecycle writes exercise every current entity table.
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "agent.model.provider=noop"
})
class JavaAgentApplicationTests extends BackendIntegrationTestBase {

    @Test
    void contextLoads() {
    }
}
