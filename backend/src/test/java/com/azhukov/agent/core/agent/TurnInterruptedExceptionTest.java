package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TurnInterruptedExceptionTest {

    @Test
    void defaultConstructor_hasMessage() {
        TurnInterruptedException ex = new TurnInterruptedException();
        assertThat(ex.getMessage()).isEqualTo("Turn interrupted by user cancellation");
    }

    @Test
    void messageConstructor_preservesMessage() {
        TurnInterruptedException ex = new TurnInterruptedException("custom reason");
        assertThat(ex.getMessage()).isEqualTo("custom reason");
    }

    @Test
    void isRuntimeException() {
        TurnInterruptedException ex = new TurnInterruptedException();
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}