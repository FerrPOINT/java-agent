package com.azhukov.agent.core.security;

import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.model.ToolResult;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ToolCallGuardrailSessionTest {
    @AfterEach void clearScope() { InterruptToken.clearCurrentSessionId(); }

    @Test
    void absentScopeCannotCreateSharedStateOrClearOtherSessions() {
        InterruptToken.clearCurrentSessionId();
        var guard = new DefaultToolCallGuardrail(new GuardrailConfig());
        assertThatThrownBy(() -> guard.beforeCall("read_file", "{}"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.reset(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> guard.removeSession(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void cleanupAndResetAffectOnlyTheirOwnSession() {
        var config = new GuardrailConfig();
        config.setHardStopAfterExactFailure(2);

        var guard = new DefaultToolCallGuardrail(config);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        InterruptToken.setCurrentSessionId(first);
        guard.afterCall("read_file", "{}", ToolResult.fail("same error"), true);
        guard.afterCall("read_file", "{}", ToolResult.fail("same error"), true);
        assertThat(guard.isHalted(first)).isTrue();
        assertThat(guard.isHalted(second)).isFalse();
        InterruptToken.setCurrentSessionId(second);
        guard.afterCall("read_file", "{}", ToolResult.fail("same error"), true);
        guard.removeSession(first);
        assertThat(guard.isHalted(first)).isFalse();
        guard.afterCall("read_file", "{}", ToolResult.fail("same error"), true);
        assertThat(guard.isHalted(second)).isTrue();
        guard.reset(first);
        assertThat(guard.isHalted(second)).isTrue();
    }
}
