package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamContextTest {

    @Test
    void isClientDisconnected_defaultsToFalse() {
        StreamContext ctx = new StreamContext();
        assertThat(ctx.isClientDisconnected()).isFalse();
    }

    @Test
    void markDisconnected_setsFlag() {
        StreamContext ctx = new StreamContext();
        ctx.markDisconnected();
        assertThat(ctx.isClientDisconnected()).isTrue();
    }

    @Test
    void markDisconnected_isIdempotent() {
        StreamContext ctx = new StreamContext();
        ctx.markDisconnected();
        ctx.markDisconnected();
        assertThat(ctx.isClientDisconnected()).isTrue();
    }

    @Test
    void multipleInstancesAreIndependent() {
        StreamContext ctx1 = new StreamContext();
        StreamContext ctx2 = new StreamContext();
        ctx1.markDisconnected();
        assertThat(ctx1.isClientDisconnected()).isTrue();
        assertThat(ctx2.isClientDisconnected()).isFalse();
    }
}