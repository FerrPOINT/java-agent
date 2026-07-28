package com.azhukov.agent.core.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingResponseHandlerTest {

    @Test
    void defaultOnToolCallsDoesNothing() {
        StreamingResponseHandler handler = new StreamingResponseHandler() {
            @Override public void onToken(String token) { }
            @Override public void onComplete() { }
            @Override public void onError(Throwable error) { }
        };
        // default method should be callable without NPE
        handler.onToolCalls(List.of());
        assertThat(true).isTrue();
    }
}
