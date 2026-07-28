package com.azhukov.agent.bot.commands;

import com.azhukov.agent.bot.commands.impl.ModelCommand;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCommandTest {

    @Test
    void noArgs_showsCurrentModel() {
        var cmd = new ModelCommand();
        BotSessionEntity session = new BotSessionEntity();
        session.setModelOverride("gpt-4o");
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).contains("gpt-4o");
    }

    @Test
    void noArgsNoOverride_showsDefault() {
        var cmd = new ModelCommand();
        BotSessionEntity session = new BotSessionEntity();
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).contains("default");
    }

    @Test
    void withArgs_setsModel() {
        var cmd = new ModelCommand();
        UpdateEvent event = makeEvent("claude-3-opus");
        String result = cmd.handle(event, new BotSessionEntity());
        assertThat(result).contains("claude-3-opus");
    }

    @Test
    void nameAndDescription_correct() {
        var cmd = new ModelCommand();
        assertThat(cmd.name()).isEqualTo("model");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user",
            "/model " + args, null, null, null, null, null, null, true, "model", args);
    }
}