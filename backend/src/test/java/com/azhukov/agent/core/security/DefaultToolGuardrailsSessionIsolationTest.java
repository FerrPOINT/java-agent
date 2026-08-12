package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for per-session state isolation in DefaultToolGuardrails.
 * Verifies that mutable state (halted, counters, etc.) is scoped per-session
 * and concurrent sessions don't interfere.
 */
class DefaultToolGuardrailsSessionIsolationTest {

    @Test
    void haltedInOneSessionDoesNotAffectAnother() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, new ApprovalQueue());

        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        // Record enough failures in session A to halt
        for (int i = 0; i < 5; i++) {
            guardrails.recordToolCall(sessionA, "terminal", "{}", false);
        }

        assertThat(guardrails.isHalted(sessionA)).isTrue();
        // Session B should not be halted
        assertThat(guardrails.isHalted(sessionB)).isFalse();
    }

    @Test
    void resetOneSessionDoesNotAffectAnother() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, new ApprovalQueue());

        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        // Record calls in both sessions
        guardrails.recordToolCall(sessionA, "read_file", "{}", true);
        guardrails.recordToolCall(sessionB, "read_file", "{}", true);
        guardrails.recordToolCall(sessionB, "read_file", "{}", true);

        // Reset session A only
        guardrails.reset(sessionA);

        // Session A counters should be cleared
        assertThat(guardrails.getToolCallCount(sessionA, "read_file")).isZero();
        // Session B counters should be preserved
        assertThat(guardrails.getToolCallCount(sessionB, "read_file")).isEqualTo(2);
    }

    @Test
    void toolCallCountIsolatedPerSession() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, new ApprovalQueue());

        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        guardrails.recordToolCall(sessionA, "read_file", "{}", true);
        guardrails.recordToolCall(sessionA, "read_file", "{}", true);
        guardrails.recordToolCall(sessionA, "read_file", "{}", true);
        guardrails.recordToolCall(sessionB, "write_file", "{}", true);

        assertThat(guardrails.getToolCallCount(sessionA, "read_file")).isEqualTo(3);
        assertThat(guardrails.getToolCallCount(sessionA, "write_file")).isZero();
        assertThat(guardrails.getToolCallCount(sessionB, "write_file")).isEqualTo(1);
        assertThat(guardrails.getToolCallCount(sessionB, "read_file")).isZero();
    }

    @Test
    void failureCountIsolatedPerSession() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, new ApprovalQueue());

        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        guardrails.recordToolCall(sessionA, "terminal", "{}", false);
        guardrails.recordToolCall(sessionA, "terminal", "{}", false);
        guardrails.recordToolCall(sessionB, "terminal", "{}", false);

        assertThat(guardrails.getToolFailureCount(sessionA, "terminal")).isEqualTo(2);
        assertThat(guardrails.getToolFailureCount(sessionB, "terminal")).isEqualTo(1);
    }

    @Test
    void checkBeforeCallUsesSessionScopedState() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, new ApprovalQueue());

        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        // Cause enough exact failures in session A to trigger block
        for (int i = 0; i < 5; i++) {
            guardrails.recordToolCall(sessionA, "terminal", "{}", false);
        }

        // checkBeforeCall for session A should return halt/block
        DefaultToolGuardrails.GuardrailDecision decisionA =
            guardrails.checkBeforeCall(sessionA, "terminal", "{}");
        assertThat(decisionA.shouldHalt()).isTrue();

        // checkBeforeCall for session B should allow
        DefaultToolGuardrails.GuardrailDecision decisionB =
            guardrails.checkBeforeCall(sessionB, "terminal", "{}");
        assertThat(decisionB.allowsExecution()).isTrue();
    }

    @Test
    void isToolAllowedForSpecificSession() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, new ApprovalQueue());

        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        // Halt session A
        for (int i = 0; i < 5; i++) {
            guardrails.recordToolCall(sessionA, "terminal", "{}", false);
        }

        assertThat(guardrails.isToolAllowed(sessionA, "terminal")).isFalse();
        assertThat(guardrails.isToolAllowed(sessionB, "terminal")).isTrue();
    }

    @Test
    void clearAllRemovesAllSessionStates() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, new ApprovalQueue());

        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        guardrails.recordToolCall(sessionA, "read_file", "{}", true);
        guardrails.recordToolCall(sessionB, "write_file", "{}", true);

        guardrails.clearAll();

        assertThat(guardrails.getToolCallCount(sessionA, "read_file")).isZero();
        assertThat(guardrails.getToolCallCount(sessionB, "write_file")).isZero();
    }

    @Test
    void removeSessionCleansUpState() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, new ApprovalQueue());

        UUID session = UUID.randomUUID();
        guardrails.recordToolCall(session, "read_file", "{}", true);
        assertThat(guardrails.getToolCallCount(session, "read_file")).isEqualTo(1);

        guardrails.removeSession(session);
        assertThat(guardrails.getToolCallCount(session, "read_file")).isZero();
    }

    @Test
    void warnedFlagIsolatedPerSession() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties, new ApprovalQueue());

        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        // Record 10+ calls in session A to trigger warning
        for (int i = 0; i < 10; i++) {
            guardrails.recordToolCall(sessionA, "read_file", "{\"p\":\"" + i + "\"}", true);
        }

        assertThat(guardrails.isWarned(sessionA)).isTrue();
        assertThat(guardrails.isWarned(sessionB)).isFalse();
    }
}