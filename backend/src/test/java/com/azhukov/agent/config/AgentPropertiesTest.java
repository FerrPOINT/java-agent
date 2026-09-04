package com.azhukov.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class AgentPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentPropertiesTestConfiguration.class);

    @Test
    void defaultValuesBindFromEmptyConfig() {
        contextRunner.run(context -> {
            AgentProperties properties = context.getBean(AgentProperties.class);

            assertThat(properties.getName()).isEqualTo("Джава агент");
            assertThat(properties.getModel().getProvider()).isEqualTo("openai-compatible");
            assertThat(properties.getCore().getMaxTurns()).isEqualTo(100);
            assertThat(properties.getSkills().getDefaultToolsets())
                    .containsExactly("hermes-cli");
            assertThat(properties.getApi().getChatCompletionToolsets())
                    .containsExactly("hermes-api-server");
            assertThat(properties.getApi().getModelName()).isEqualTo("java-agent");
            assertThat(properties.getApi().getCorsOrigins()).isEmpty();
            assertThat(properties.getApi().isDirectModelRequests()).isFalse();
            assertThat(properties.getApi().getModelRoutes()).isEmpty();
            assertThat(properties.getMemory().isMemoryEnabled()).isTrue();
            assertThat(properties.getMemory().isUserProfileEnabled()).isTrue();
        });
    }

    @Test
    void customYamlValuesOverrideDefaults() {
        contextRunner
                .withPropertyValues(
                        "agent.name=Custom Agent",
                        "agent.model.provider=anthropic",
                        "agent.core.maxTurns=42",
                        "agent.skills.defaultToolsets=web,file",
                        "agent.api.modelName=custom-agent",
                        "agent.api.chatCompletionToolsets=web",
                        "agent.api.corsOrigins=https://app.example,https://admin.example",
                        "agent.api.directModelRequests=true",
                        "agent.memory.memoryEnabled=false",
                        "agent.memory.userProfileEnabled=false",
                        "agent.api.modelRoutes.fast.model=fast-model",
                        "agent.api.modelRoutes.fast.provider=openrouter",
                        "agent.api.modelRoutes.fast.baseUrl=https://openrouter.example/v1",
                        "agent.api.modelRoutes.fast.apiKey=route-secret"
                )
                .run(context -> {
                    AgentProperties properties = context.getBean(AgentProperties.class);

                    assertThat(properties.getName()).isEqualTo("Custom Agent");
                    assertThat(properties.getModel().getProvider()).isEqualTo("anthropic");
                    assertThat(properties.getCore().getMaxTurns()).isEqualTo(42);
                    assertThat(properties.getSkills().getDefaultToolsets()).containsExactly("web", "file");
                    assertThat(properties.getApi().getModelName()).isEqualTo("custom-agent");
                    assertThat(properties.getApi().getChatCompletionToolsets()).containsExactly("web");
                    assertThat(properties.getApi().getCorsOrigins())
                            .containsExactly("https://app.example", "https://admin.example");
                    assertThat(properties.getApi().isDirectModelRequests()).isTrue();
                    assertThat(properties.getMemory().isMemoryEnabled()).isFalse();
                    assertThat(properties.getMemory().isUserProfileEnabled()).isFalse();
                    assertThat(properties.getApi().getModelRoutes()).containsKey("fast");
                    AgentProperties.ApiProperties.ModelRouteProperties route =
                            properties.getApi().getModelRoutes().get("fast");
                    assertThat(route.getModel()).isEqualTo("fast-model");
                    assertThat(route.getProvider()).isEqualTo("openrouter");
                    assertThat(route.getBaseUrl()).isEqualTo("https://openrouter.example/v1");
                    assertThat(route.getApiKey()).isEqualTo("route-secret");
                });
    }

    @Test
    void nestedGatewayTelegramPropertiesBind() {
        contextRunner
                .withPropertyValues(
                        "agent.gateway.telegram.botToken=secret-token-123",
                        "agent.gateway.telegram.timeoutSeconds=60"
                )
                .run(context -> {
                    AgentProperties properties = context.getBean(AgentProperties.class);

                    assertThat(properties.getGateway().getTelegram().getBotToken()).isEqualTo("secret-token-123");
                    assertThat(properties.getGateway().getTelegram().getTimeoutSeconds()).isEqualTo(60);
                });
    }

    @Test
    void programmaticallySettingValuesWorks() {
        AgentProperties properties = new AgentProperties();

        properties.setName("Programmatic Agent");
        properties.getModel().setProvider("localai");
        properties.getCore().setMaxTurns(7);
        properties.getGateway().getTelegram().setBotToken("prog-token");
        properties.getGateway().getTelegram().setTimeoutSeconds(15);

        assertThat(properties.getName()).isEqualTo("Programmatic Agent");
        assertThat(properties.getModel().getProvider()).isEqualTo("localai");
        assertThat(properties.getCore().getMaxTurns()).isEqualTo(7);
        assertThat(properties.getGateway().getTelegram().getBotToken()).isEqualTo("prog-token");
        assertThat(properties.getGateway().getTelegram().getTimeoutSeconds()).isEqualTo(15);
    }

    @Configuration
    @EnableConfigurationProperties(AgentProperties.class)
    static class AgentPropertiesTestConfiguration {
    }
}
