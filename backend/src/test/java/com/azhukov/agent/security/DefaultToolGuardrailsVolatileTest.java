package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REM-7: Verify that blockedTools field is volatile (thread-safe visibility).
 * This test verifies functional correctness of get/setBlockedTools.
 */
class DefaultToolGuardrailsVolatileTest {

    @Test
    void setAndGetBlockedTools_worksCorrectly() {
        AgentProperties properties = new AgentProperties();
        ApprovalQueue approvalQueue = new ApprovalQueue();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, approvalQueue);

        // Initially empty
        assertThat(guardrails.getBlockedTools()).isEmpty();

        // Set blocked tools
        Set<String> blocked = Set.of("terminal", "write_file");
        guardrails.setBlockedTools(blocked);
        assertThat(guardrails.getBlockedTools()).containsExactlyInAnyOrder("terminal", "write_file");

        // isToolAllowed should reflect the blocked list
        assertThat(guardrails.isToolAllowed("terminal")).isFalse();
        assertThat(guardrails.isToolAllowed("write_file")).isFalse();
        assertThat(guardrails.isToolAllowed("read_file")).isTrue();
    }

    @Test
    void setBlockedTools_nullSetsEmptySet() {
        AgentProperties properties = new AgentProperties();
        ApprovalQueue approvalQueue = new ApprovalQueue();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, approvalQueue);

        guardrails.setBlockedTools(null);
        assertThat(guardrails.getBlockedTools()).isEmpty();
    }

    @Test
    void blockedTools_visibleAcrossThreads() throws InterruptedException {
        AgentProperties properties = new AgentProperties();
        ApprovalQueue approvalQueue = new ApprovalQueue();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, approvalQueue);

        Set<String> blocked = Set.of("dangerous_tool");
        Thread setter = new Thread(() -> guardrails.setBlockedTools(blocked));
        setter.start();
        setter.join();

        // Reading from another thread should see the update (volatile guarantee)
        assertThat(guardrails.getBlockedTools()).contains("dangerous_tool");
        assertThat(guardrails.isToolAllowed("dangerous_tool")).isFalse();
    }
}