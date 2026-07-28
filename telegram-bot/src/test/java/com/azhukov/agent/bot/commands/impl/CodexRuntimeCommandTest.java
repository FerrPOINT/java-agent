package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodexRuntimeCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new CodexRuntimeCommand();
        assertThat(cmd.name()).isEqualTo("codex_runtime");
        assertThat(cmd.description()).isEqualTo("Codex runtime (not supported)");
    }

    @Test
    void handleReturnsStubMessage() {
        var cmd = new CodexRuntimeCommand();
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).isEqualTo("Codex runtime is not supported in this build.");
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/codex_runtime", null, null, null, null, null, null, true, "codex_runtime", "");
    }
}