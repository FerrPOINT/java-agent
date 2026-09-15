package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.azhukov.agent.core.model.Message;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-d (Hermes #110769 stale-stream parity): a stream that keeps delivering
 * events stays alive arbitrarily long; a stream that goes silent is declared
 * wedged after agent.model.stream-stall-seconds instead of hanging until the
 * flat timeout (or forever).
 */
@ExtendWith(MockitoExtension.class)
class StaleStreamWatchdogTest {

    @Mock
    private dev.langchain4j.model.chat.ChatModel chatModel;

    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getModel().setTimeoutSeconds(3);      // legacy overall silence timeout
        properties.getModel().setStreamStallSeconds(1);  // 1s stall window for the test
    }

    private LangChain4jModelClient client(StreamingChatModel streaming) {
        return new LangChain4jModelClient(chatModel, streaming, properties, null, null, null);
    }

    private static final List<Message> MESSAGES = List.of(Message.user("hi"));

    /** A streaming model that never fires any callback — a wedged connection. */
    private static final StreamingChatModel SILENT = new StreamingChatModel() {
        @Override
        public void doChat(ChatRequest request,
                           dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
            // no-op
        }
    };

    @Test
    void wedgedStreamFailsAfterStallWindow() {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        CountDownLatch errorLatch = new CountDownLatch(1);

        long start = System.nanoTime();
        try {
            client(SILENT).stream(MESSAGES, null, ModelRequestOptions.empty(),
                new StreamingResponseHandler() {
                    @Override public void onToken(String token) { }
                    @Override public void onError(Throwable t) { seen.set(t); errorLatch.countDown(); }
                    @Override public void onComplete() { }
                });
        } catch (RuntimeException e) {
            seen.set(e.getCause() != null ? e.getCause() : e);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getMessage()).contains("wedged");
        // ~1s stall window, not the 3s flat timeout
        assertThat(elapsedMs).isBetween(700L, 5_000L);
    }

    @Test
    void livelyStreamKeepsResettingTheStallClock() {
        // One token every 300ms over ~2.4s: longer than the 1s stall window and
        // longer than the 3s flat timeout would be if counted from turn start —
        // but each token resets the clock, so the stream must COMPLETE.
        StreamingChatModel lively = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request,
                               dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                Thread t = new Thread(() -> {
                    try {
                        for (int i = 0; i < 8; i++) {
                            Thread.sleep(300);
                            handler.onPartialResponse("tok");
                        }
                        handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(dev.langchain4j.data.message.AiMessage.builder().build())
                            .build());
                    } catch (InterruptedException ignored) {
                    } catch (RuntimeException e) {
                        // surface emitter failures in test output
                        System.err.println("emitter failed: " + e);
                    }
                });
                t.setDaemon(true);
                t.start();
            }
        };
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            client(lively).stream(MESSAGES, null, ModelRequestOptions.empty(),
                new StreamingResponseHandler() {
                    @Override public void onToken(String token) { }
                    @Override public void onError(Throwable t) { err.set(t); }
                    @Override public void onComplete() { }
                });
        } catch (RuntimeException e) {
            err.set(e);
        }
        assertThat(err.get()).as("lively stream must not fail, got: %s", err.get()).isNull();
    }

    @Test
    void disabledWatchdogKeepsLegacyFlatTimeout() {
        properties.getModel().setStreamStallSeconds(0);
        properties.getModel().setTimeoutSeconds(1);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        long start = System.nanoTime();
        try {
            client(SILENT).stream(MESSAGES, null, ModelRequestOptions.empty(),
                new StreamingResponseHandler() {
                    @Override public void onToken(String token) { }
                    @Override public void onError(Throwable t) { seen.set(t); }
                    @Override public void onComplete() { }
                });
        } catch (RuntimeException e) {
            seen.set(e.getCause() != null ? e.getCause() : e);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // legacy 1s silence timeout still applies; stall watchdog disabled
        assertThat(seen.get()).isNotNull();
        assertThat(elapsedMs).isLessThan(10_000L);
    }
}
