package com.azhukov.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdSecurityStartupValidatorTest {

    @Test
    void prodProfileRequiresApiKey() {
        AgentProperties properties = new AgentProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ProdSecurityStartupValidator validator = new ProdSecurityStartupValidator(properties, environment);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("agent.security.api-key");
    }

    @Test
    void prodProfileAllowsConfiguredApiKey() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApiKey("secret-key-123456");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ProdSecurityStartupValidator validator = new ProdSecurityStartupValidator(properties, environment);

        assertThatCode(() -> validator.run(new DefaultApplicationArguments()))
            .doesNotThrowAnyException();
    }

    @Test
    void prodProfileRejectsShortApiKey() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApiKey("short-key");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ProdSecurityStartupValidator validator = new ProdSecurityStartupValidator(properties, environment);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 16 characters");
    }

    @Test
    void nonProdProfileKeepsDevAuthDisabledMode() {
        AgentProperties properties = new AgentProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        ProdSecurityStartupValidator validator = new ProdSecurityStartupValidator(properties, environment);

        assertThatCode(() -> validator.run(new DefaultApplicationArguments()))
            .doesNotThrowAnyException();
    }
}
