package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.TurnInterruptedException;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.Message;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class LangChain4jModelClientStreamingTest {

    @AfterEach
    void cleanup() {
        InterruptToken.clearCurrentSessionId();
        InterruptToken.setInstance(null);
    }

    @Test
    void streamsTokens() {
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        AgentProperties props = new AgentProperties();
        AtomicReference<LangChain4jModelClient.Usage> usage = new AtomicReference<>();
        LangChain4jModelClient client = new LangChain4jModelClient(null, streaming, props, usage::set, null, null);

        List<String> tokens = new ArrayList<>();
        List<String> completes = new ArrayList<>();

        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onPartialResponse("one");
            h.onPartialResponse("two");
            h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("final")).build());
            return null;
        }).when(streaming).doChat(any(ChatRequest.class), any());

        client.stream(List.of(Message.user("hi")), List.of(), new StreamingResponseHandler() {
            @Override public void onToken(String token) { tokens.add(token); }
            @Override public void onComplete() { completes.add("done"); }
            @Override public void onError(Throwable error) { }
        });

        assertThat(tokens).containsExactly("one", "two");
        assertThat(completes).containsExactly("done");
    }

    @Test
    void usageRecordTotalTokens() {
        var u = new LangChain4jModelClient.Usage("p", "m", 10, 20);
        assertThat(u.totalTokens()).isEqualTo(30);
    }

    @Test
    void stream_interruptedMidToken_callsOnCompleteNotOnError() {
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        AgentProperties props = new AgentProperties();
        LangChain4jModelClient client = new LangChain4jModelClient(null, streaming, props, u -> {}, null, null);

        // Set up InterruptToken with a cancelled session
        InterruptToken token = new InterruptToken();
        InterruptToken.setInstance(token);
        UUID sessionId = UUID.randomUUID();
        token.cancel(sessionId);
        InterruptToken.setCurrentSessionId(sessionId);

        List<String> tokens = new ArrayList<>();
        List<String> completes = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();

        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            // First token — should throw TurnInterruptedException because isCancelledGlobally() is true
            try {
                h.onPartialResponse("should-not-arrive");
            } catch (TurnInterruptedException e) {
                // Simulate what the LangChain4j framework does: route to onError
                h.onError(e);
            }
            return null;
        }).when(streaming).doChat(any(ChatRequest.class), any());

        client.stream(List.of(Message.user("hi")), List.of(), new StreamingResponseHandler() {
            @Override public void onToken(String token) { tokens.add(token); }
            @Override public void onComplete() { completes.add("done"); }
            @Override public void onError(Throwable error) { errors.add(error); }
        });

        // When interrupted, onComplete should be called (not onError), and no tokens should arrive
        assertThat(tokens).isEmpty();
        assertThat(completes).containsExactly("done");
        assertThat(errors).isEmpty();
    }

    @Test
    void stream_notInterrupted_flowsNormally() {
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        AgentProperties props = new AgentProperties();
        LangChain4jModelClient client = new LangChain4jModelClient(null, streaming, props, u -> {}, null, null);

        // Set up InterruptToken with a non-cancelled session
        InterruptToken token = new InterruptToken();
        InterruptToken.setInstance(token);
        UUID sessionId = UUID.randomUUID();
        InterruptToken.setCurrentSessionId(sessionId);

        List<String> tokens = new ArrayList<>();
        List<String> completes = new ArrayList<>();

        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onPartialResponse("hello");
            h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("hello")).build());
            return null;
        }).when(streaming).doChat(any(ChatRequest.class), any());

        client.stream(List.of(Message.user("hi")), List.of(), new StreamingResponseHandler() {
            @Override public void onToken(String token) { tokens.add(token); }
            @Override public void onComplete() { completes.add("done"); }
            @Override public void onError(Throwable error) { }
        });

        assertThat(tokens).containsExactly("hello");
        assertThat(completes).containsExactly("done");
    }
}
