package com.azhukov.agent.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpModelClientTest {

    @Test
    void completeEchoesLastMessage() {
        NoOpModelClient c = new NoOpModelClient();
        var r = c.complete(java.util.List.of(
            com.azhukov.agent.core.model.Message.user("hello")
        ), java.util.List.of());
        assertThat(r.content()).contains("NoOp response:").contains("hello");
    }

    @Test
    void analyzeImageReportsLength() {
        NoOpModelClient c = new NoOpModelClient();
        assertThat(c.analyzeImage("abc", "prompt")).contains("image length=3").contains("prompt=prompt");
    }
}
