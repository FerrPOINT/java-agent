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
                    .containsExactly("web", "file", "browser", "terminal", "coding", "memory", "core", "delegation", "gateway", "todo", "skills");
        });
    }

    @Test
    void customYamlValuesOverrideDefaults() {
        contextRunner
                .withPropertyValues(
                        "agent.name=Custom Agent",
                        "agent.model.provider=anthropic",
                        "agent.core.maxTurns=42",
                        "agent.skills.defaultToolsets=web,file"
                )
                .run(context -> {
                    AgentProperties properties = context.getBean(AgentProperties.class);

                    assertThat(properties.getName()).isEqualTo("Custom Agent");
                    assertThat(properties.getModel().getProvider()).isEqualTo("anthropic");
                    assertThat(properties.getCore().getMaxTurns()).isEqualTo(42);
                    assertThat(properties.getSkills().getDefaultToolsets()).containsExactly("web", "file");
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
