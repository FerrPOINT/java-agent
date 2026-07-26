package com.azhukov.agent.core.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentStateTest {

    @Test
    void setGetRemoveSnapshot() {
        DefaultAgentState s = new DefaultAgentState();
        s.set("k", "v");
        assertThat(s.get("k")).hasValue("v");

        s.set("k", null);
        assertThat(s.get("k")).isEmpty();

        s.set("a", "b");
        assertThat(s.snapshot()).containsEntry("a", "b");

        s.remove("a");
        assertThat(s.get("a")).isEmpty();
    }
}
