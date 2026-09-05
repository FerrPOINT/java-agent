package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.ToolCall;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M31 regression: after a stream timeout the caller has moved on — late
 * callbacks (tokens/completion/error) must be dropped, not forwarded.
 */
class LangChain4jModelClientStreamTimeoutTest {

    /** Stub streaming model that never calls any handler callback. */
    static class HangingStreamingModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            // deliberately hang — never completes
        }
    }

    /** Stub that fires a late completion AFTER the timeout window. */
    static class LateCompletionModel implements StreamingChatModel {
        private final long delayMs;
        LateCompletionModel(long delayMs) { this.delayMs = delayMs; }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            Thread t = new Thread(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
                handler.onPartialResponse("late");
                handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("late done"))
                    .finishReason(dev.langchain4j.model.output.FinishReason.STOP)
                    .build());
            }, "late-completion");
            t.setDaemon(true);
            t.start();
        }
    }

    private AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.getModel().setTimeoutSeconds(1);
        return p;
    }

    private LangChain4jModelClient client(StreamingChatModel model) {
        return new LangChain4jModelClient(null, model, props(), null, null, null);
    }

    @Test
    void hangingStreamTimesOutAndThrows() {
        var handler = new RecordingHandler();
        assertThatThrownBy(() -> client(new HangingStreamingModel())
            .stream(List.of(com.azhukov.agent.core.model.Message.user("hi")), List.of(), handler))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("timed out");
        assertThat(handler.error.get()).isNotNull();
    }

    @Test
    void lateCallbacksAfterTimeoutAreDropped() {
        var handler = new RecordingHandler();
        // timeout = 1s; late completion arrives at 2.5s
        assertThatThrownBy(() -> client(new LateCompletionModel(2500))
            .stream(List.of(com.azhukov.agent.core.model.Message.user("hi")), List.of(), handler))
            .hasMessageContaining("timed out");
        // Give the late thread a moment to fire its callbacks
        assertThat(handler.tokens.get()).as("late tokens must be dropped").isNull();
        assertThat(handler.completed).as("late completion must be dropped").isFalse();
    }

    static class RecordingHandler implements StreamingResponseHandler {
        final AtomicReference<String> tokens = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile boolean completed = false;
        final CompletableFuture<Void> done = new CompletableFuture<>();

        @Override public void onToken(String token) { tokens.set(token); }
        @Override public void onToolCalls(List<ToolCall> calls) { }
        @Override public void onComplete(String finishReason, Long outputTokens) {
            completed = true;
            done.complete(null);
        }
        @Override public void onComplete() { completed = true; done.complete(null); }
        @Override public void onError(Throwable t) { error.set(t); done.complete(null); }
    }
}
