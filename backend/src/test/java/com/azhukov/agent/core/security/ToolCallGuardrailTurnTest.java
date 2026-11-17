package com.azhukov.agent.core.security;

import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ToolCallGuardrailTurnTest {
    @AfterEach void clearScope() { InterruptToken.clearCurrentSessionId(); }

    @Test
    void turnResetAndCleanupCannotResetAnotherSessionsQuota() {
        var config = new GuardrailConfig();
        config.setMaxWebSearches(1);
        var guard = new DefaultToolCallGuardrail(config);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        InterruptToken.setCurrentSessionId(first);
        assertThat(guard.beforeCall("web_search", "{}").isAllow()).isTrue();
        InterruptToken.setCurrentSessionId(second);
        guard.reset(second);
        assertThat(guard.beforeCall("web_search", "{}").isAllow()).isTrue();
        InterruptToken.setCurrentSessionId(first);
        assertThat(guard.beforeCall("web_search", "{}").code()).isEqualTo("loop_web_search_cap");
        guard.removeSession(second);
        assertThat(guard.beforeCall("web_search", "{}").isBlockOrHalt()).isTrue();
        guard.reset(first);
        assertThat(guard.beforeCall("web_search", "{}").isAllow()).isTrue();
    }

    @Test
    void batchCannotOvershootSpawnBudgetAndManagementStillWorksAtLimit() {
        var config = new GuardrailConfig();
        config.setMaxSubagents(3);
        var guard = new DefaultToolCallGuardrail(config);
        InterruptToken.setCurrentSessionId(UUID.randomUUID());
        assertThat(guard.beforeCall("delegate_task", "{\"tasks\":[{},{}]}").isAllow()).isTrue();
        assertThat(guard.beforeCall("delegate_task", "{\"tasks\":[{},{}]}").code()).isEqualTo("loop_delegate_task_cap");
        assertThat(guard.beforeCall("delegate_task", "{}").isAllow()).isTrue();
        for (String action : new String[]{"list", "steer", "stop"}) {
            assertThat(guard.beforeCall("delegate_task", "{\"action\":\"" + action + "\"}").isAllow()).isTrue();
        }
        assertThat(guard.beforeCall("delegate_task", "{}").isBlockOrHalt()).isTrue();
    }

    @Test
    void failureIdentityIncludesDiagnosticAndCanonicalArguments() {
        var config = new GuardrailConfig();
        config.setHardStopAfterExactFailure(2);
        var guard = new DefaultToolCallGuardrail(config);
        UUID session = UUID.randomUUID();
        InterruptToken.setCurrentSessionId(session);
        guard.afterCall("terminal", "{\"a\":1,\"b\":2}", ToolResult.fail("first"), true);
        guard.afterCall("terminal", "{\"a\":1,\"b\":2}", ToolResult.fail("different"), true);
        assertThat(guard.isHalted(session)).isFalse();
        assertThat(guard.afterCall("terminal", "{\"b\":2,\"a\":1}", ToolResult.fail("first"), true).isBlockOrHalt()).isTrue();
    }

    @Test
    void repeatedReadsAreAdvisoryAndNewContentStartsFresh() {
        var guard = new DefaultToolCallGuardrail(new GuardrailConfig());
        InterruptToken.setCurrentSessionId(UUID.randomUUID());
        assertThat(guard.afterCall("read_file", "{}", ToolResult.ok("one"), false).isAllow()).isTrue();
        assertThat(guard.afterCall("read_file", "{}", ToolResult.ok("one"), false).code()).isEqualTo("idempotent_no_progress");
        assertThat(guard.beforeCall("read_file", "{}").isAllow()).isTrue();
        assertThat(guard.afterCall("read_file", "{}", ToolResult.ok("two"), false).isAllow()).isTrue();
    }

    @Test
    void resultReferencesAreIsolatedAndRequireExactBytes() {
        var guard = new DefaultToolCallGuardrail(new GuardrailConfig());
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        String original = "{\"text\":\"" + "x".repeat(600) + "\"}";
        InterruptToken.setCurrentSessionId(first);
        assertThat(guard.resultReferenceStub("read_file", "{}", original, false)).isNull();
        InterruptToken.setCurrentSessionId(second);
        assertThat(guard.resultReferenceStub("read_file", "{}", original, false)).isNull();
        InterruptToken.setCurrentSessionId(first);
        assertThat(guard.resultReferenceStub("read_file", "{}", original, false)).contains("Duplicate result");
        assertThat(guard.resultReferenceStub("read_file", "{}", original + " ", false)).isNull();
        guard.resultReferenceStub("read_file", "{}", null, false);
        assertThat(guard.resultReferenceStub("read_file", "{}", original + " ", false)).isNull();
    }
}
