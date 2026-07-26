package com.azhukov.agent.core.state;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TurnStateManagerTest {

    @Test
    void getOrStartCreatesStatePerSessionAndTurn() {
        TurnStateManager mgr = new TurnStateManager();
        UUID s1 = UUID.randomUUID();
        TurnState st1 = mgr.getOrStart(s1, 1);
        TurnState st2 = mgr.getOrStart(s1, 1);
        TurnState st3 = mgr.getOrStart(s1, 2);
        assertThat(st1).isSameAs(st2);
        assertThat(st1).isNotSameAs(st3);
    }

    @Test
    void getReturnsNullForUnknown() {
        TurnStateManager mgr = new TurnStateManager();
        assertThat(mgr.get(UUID.randomUUID(), 1)).isNull();
    }

    @Test
    void clearRemovesSessionStates() {
        TurnStateManager mgr = new TurnStateManager();
        UUID s1 = UUID.randomUUID();
        mgr.getOrStart(s1, 1);
        mgr.clear(s1);
        assertThat(mgr.get(s1, 1)).isNull();
    }
}
