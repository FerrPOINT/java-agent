package com.azhukov.agent.config;

import com.azhukov.agent.config.split.AgentSecurityConfig;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.security.ToolApprovalPolicy;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

class AgentConfigNoDuplicateBeanTest {
    @Test
    void securityConfigurationHasOneApprovalPolicyAndOneLoopGuardrail() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentProperties.class, AgentProperties::new);
            context.registerBean(ApprovalQueue.class, ApprovalQueue::new);
            context.register(AgentSecurityConfig.class);
            context.refresh();
            assertThat(context.getBeansOfType(ToolApprovalPolicy.class)).hasSize(1);
            assertThat(context.getBeansOfType(ToolCallGuardrail.class)).hasSize(1);
        }
    }
}
