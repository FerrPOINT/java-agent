package com.azhukov.agent.config;

import com.azhukov.agent.config.split.SecurityConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * NEW-3: Verify that the duplicate @Bean method for ToolGuardrails has been removed.
 * Only one toolGuardrails bean method should exist in the security config.
 */
class AgentConfigNoDuplicateBeanTest {

    @Test
    void onlyOneToolGuardrailsBeanMethodExists() throws NoSuchMethodException {
        SecurityConfig config = new SecurityConfig();

        // The toolGuardrails method should exist
        assertThat(config.getClass().getMethod("toolGuardrails",
            AgentProperties.class, com.azhukov.agent.security.ApprovalQueue.class))
            .isNotNull();

        // The legacy duplicate method should NOT exist
        assertThatThrownBy(() ->
            config.getClass().getMethod("legacyToolGuardrails",
                AgentProperties.class, com.azhukov.agent.security.ApprovalQueue.class))
            .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void toolGuardrails_bean_returnsDefaultToolGuardrails() {
        SecurityConfig config = new SecurityConfig();
        AgentProperties properties = new AgentProperties();

        Object bean = config.toolGuardrails(properties, mock(com.azhukov.agent.security.ApprovalQueue.class));
        assertThat(bean).isInstanceOf(com.azhukov.agent.security.DefaultToolGuardrails.class);
    }
}