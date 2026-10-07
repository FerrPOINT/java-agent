package com.azhukov.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelProfileEnvironmentTest {

    private StandardEnvironment environment(String profile, Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("deployment", variables));
        environment.getPropertySources().addFirst(new MapPropertySource("config", Map.of(
            "spring.config.location", "file:src/main/resources/application.yml")));
        environment.setActiveProfiles(profile);
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    @Test
    void devUsesTheExplicitCommonModelEndpointAndKey() {
        var environment = environment("dev", Map.of(
            "AGENT_MODEL_BASE_URL", "https://model.example/v1",
            "AGENT_MODEL_API_KEY", "fixture-common-key",
            "OLLAMA_BASE_URL", "https://legacy.example/v1",
            "OLLAMA_API_KEY", "fixture-legacy-key"));
        assertThat(environment.getProperty("agent.model.base-url")).isEqualTo("https://model.example/v1");
        assertThat(environment.getProperty("agent.model.api-key")).isEqualTo("fixture-common-key");
    }

    @Test
    void devRetainsTheLegacyOllamaEnvironmentWhenCommonVariablesAreAbsent() {
        var environment = environment("dev", Map.of(
            "OLLAMA_BASE_URL", "https://legacy.example/v1", "OLLAMA_API_KEY", "fixture-legacy-key"));
        assertThat(environment.getProperty("agent.model.base-url")).isEqualTo("https://legacy.example/v1");
        assertThat(environment.getProperty("agent.model.api-key")).isEqualTo("fixture-legacy-key");
    }

    @Test
    void prodUsesTheExplicitCommonModelKey() {
        var environment = environment("prod", Map.of(
            "AGENT_MODEL_API_KEY", "fixture-common-key", "OPENAI_API_KEY", "fixture-legacy-key"));
        assertThat(environment.getProperty("agent.model.api-key")).isEqualTo("fixture-common-key");
    }

    @Test
    void prodRetainsTheLegacyOpenAiKeyWhenCommonVariableIsAbsent() {
        var environment = environment("prod", Map.of("OPENAI_API_KEY", "fixture-legacy-key"));
        assertThat(environment.getProperty("agent.model.api-key")).isEqualTo("fixture-legacy-key");
    }

    @Test
    void devRetainsItsOperationalDefaultsWithoutModelVariables() {
        var environment = environment("dev", Map.of());
        assertThat(environment.getProperty("agent.model.base-url")).isEqualTo("https://ollama.com/v1");
        assertThat(environment.getProperty("agent.model.api-key")).isEmpty();
    }

    @Test
    void prodRetainsItsEmptyKeyWithoutModelVariables() {
        assertThat(environment("prod", Map.of()).getProperty("agent.model.api-key")).isEmpty();
    }
}
