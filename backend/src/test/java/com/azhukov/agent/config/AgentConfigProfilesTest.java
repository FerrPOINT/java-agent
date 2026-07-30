package com.azhukov.agent.config;

import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.telegram.TelegramLongPollingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
class AgentConfigProfilesTest {

    @SpringBootTest
    @ActiveProfiles("test")
    static class TestProfileTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void contextLoads() {
            assertThat(context).isNotNull();
        }

        @Test
        void keyBeansExist() {
            assertThat(context.getBean(ModelClient.class)).isNotNull();
            assertThat(context.getBean(ToolRegistry.class)).isNotNull();
            assertThat(context.getBean("agentRuntime", AgentRuntime.class)).isNotNull();
            assertThat(context.getBean(GatewayRoutingService.class)).isNotNull();
        }
    }

    @SpringBootTest
    @ActiveProfiles({"noop"})
    @TestPropertySource(properties = {
        "agent.model.provider=noop",
        "agent.memory.enabled=false",
        "agent.skills.enabled=false"
    })
    @DirtiesContext
    static class NoOpProfileTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void noOpModelClientExists() {
            assertThat(context.getBean(NoOpModelClient.class)).isNotNull();
            assertThat(context.getBean(ModelClient.class)).isInstanceOf(NoOpModelClient.class);
        }
    }

    @SpringBootTest
    @ActiveProfiles("dev")
    @TestPropertySource(properties = {
        "agent.gateway.telegram.long-polling.enabled=false",
        "agent.model.provider=noop",
        "agent.memory.enabled=false",
        "agent.skills.enabled=false",
        "agent.chromium.auto-start=false",
        "agent.chromium.auto-install=false",
        "agent.mcp.enabled=false",
        "agent.mcp.servers=",
        "spring.flyway.enabled=false"
    })
    static class DevProfileLongPollingAbsentTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void telegramLongPollingBeanAbsentWhenPropertyFalse() {
            assertThat(context.containsBean("telegramLongPollingService")).isFalse();
            assertThat(context.getBeanNamesForType(TelegramLongPollingService.class)).isEmpty();
        }
    }

    @SpringBootTest
    @ActiveProfiles("dev")
    @TestPropertySource(properties = {
        "agent.gateway.telegram.long-polling.enabled=true",
        "agent.model.provider=noop",
        "agent.memory.enabled=false",
        "agent.skills.enabled=false",
        "agent.chromium.auto-start=false",
        "agent.chromium.auto-install=false",
        "agent.mcp.enabled=false",
        "agent.mcp.servers=",
        "spring.flyway.enabled=false"
    })
    static class DevProfileLongPollingPresentTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void telegramLongPollingBeanPresentWhenPropertyTrue() {
            assertThat(context.getBean(TelegramLongPollingService.class)).isNotNull();
        }
    }
}
