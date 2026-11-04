package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpServerAutoConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    static class Fixture {
        @Bean AgentProperties properties() {
            var p = new AgentProperties();
            p.getMcp().getServer().setEnabled(true);
            p.getMcp().getServer().setTransport("sse");
            return p;
        }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean ToolRegistry registry() {
            var registry = mock(ToolRegistry.class);
            when(registry.getDefinitions()).thenReturn(List.of());
            return registry;
        }
        @Bean McpServerService server(AgentProperties p, ObjectMapper m, ToolRegistry r) {
            return new McpServerService(p, m, r);
        }
    }

    @Test
    void springRegistersSseServletBeforeContextRefresh() {
        new ApplicationContextRunner()
            .withUserConfiguration(Fixture.class, McpServerAutoConfiguration.class)
            .withPropertyValues("agent.mcp.server.enabled=true", "agent.mcp.server.transport=sse")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(ServletRegistrationBean.class)).containsKey("mcpSseServletRegistration");
                assertThat(context.getBean(McpServerService.class).isRunning()).isTrue();
            });
    }
}
