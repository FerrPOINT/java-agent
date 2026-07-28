package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.Message;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class LangChain4jModelClientStreamingTest {

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
}
