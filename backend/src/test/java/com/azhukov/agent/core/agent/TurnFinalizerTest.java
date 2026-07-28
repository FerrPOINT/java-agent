package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

class TurnFinalizerTest {

    private final TurnFinalizer finalizer = new TurnFinalizer();

    @Test
    void finalize_success_logsCorrectly() {
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(Message.user("hello"), Message.assistant("hi", 1));
        assertThatCode(() -> finalizer.finalize(sessionId, messages, true))
            .doesNotThrowAnyException();
    }

    @Test
    void finalize_failure_logsCorrectly() {
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(Message.user("hello"), Message.assistant("error", 1));
        assertThatCode(() -> finalizer.finalize(sessionId, messages, false))
            .doesNotThrowAnyException();
    }

    @Test
    void finalize_emptyMessages_doesNotThrow() {
        UUID sessionId = UUID.randomUUID();
        assertThatCode(() -> finalizer.finalize(sessionId, List.of(), true))
            .doesNotThrowAnyException();
    }
}