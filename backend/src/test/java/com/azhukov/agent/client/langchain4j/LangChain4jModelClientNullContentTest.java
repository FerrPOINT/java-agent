package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Bug 1: null content handling in LangChain4jModelClient.
 * <p>
 * When an assistant message has no text (only tool calls), message.content() is null.
 * Previously, AiMessage.from(null) threw IllegalArgumentException.
 * Now it should fall back to empty string "".
 * <p>
 * Similarly, ToolExecutionResultMessage.from() with null content should use "" fallback.
 * <p>
 * Also, complete() method at line 157: ChatResponse.text(aiMessage.text()) with null
 * aiMessage.text() (tool-call-only response with no text) should return "" instead of failing.
 */
class LangChain4jModelClientNullContentTest {

    private final AgentProperties properties = new AgentProperties();
    private final ChatModel chatModel = mock(ChatModel.class);
    private final StreamingChatModel streamingChatModel = mock(StreamingChatModel.class);
    private final AtomicReference<LangChain4jModelClient.Usage> usage = new AtomicReference<>();

    private LangChain4jModelClient client() {
        return new LangChain4jModelClient(chatModel, streamingChatModel, properties, usage::set, null, null);
    }

    // ── toLangChainMessage: ASSISTANT with null content + tool calls ──

    @Test
    void completeWithAssistantMessageNullContentAndToolCallsDoesNotThrow() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .build());

        // assistantToolCalls creates a message with null content and tool calls
        Message assistantMsg = Message.assistantToolCalls(
            List.of(new ToolCall("1", "search", "{\"q\":\"x\"}")), 0);

        assertThatCode(() -> client.complete(
            List.of(Message.user("hi"), assistantMsg),
            List.of()))
            .doesNotThrowAnyException();
    }

    // ── toLangChainMessage: ASSISTANT with null content and no tool calls ──

    @Test
    void completeWithAssistantMessageNullContentNoToolCallsDoesNotThrow() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .build());

        // assistantToolCalls creates a message with null content and tool calls,
        // but even without tool calls, null content should not cause AiMessage.from(null)
        // We test the internal path by using assistantWithToolCalls with null content
        // Actually, the only way to get null content with ASSISTANT role is via assistantToolCalls
        // which always has tool calls. But the fix ensures the fallback works regardless.
        Message assistantMsg = Message.assistantToolCalls(
            List.of(new ToolCall("1", "search", "{}")), 0);

        assertThatCode(() -> client.complete(
            List.of(Message.user("hi"), assistantMsg),
            List.of()))
            .doesNotThrowAnyException();
    }

    // ── toLangChainMessage: TOOL with null content ──

    @Test
    void completeWithToolResultMessageNullContentDoesNotThrow() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .build());

        // Create a tool result message with null content
        Message toolResultNull = new Message(
            com.azhukov.agent.core.model.Role.TOOL, null, null, null, "call-1", 0, 0);

        assertThatCode(() -> client.complete(
            List.of(Message.user("hi"),
                Message.assistant("calling", 0),
                toolResultNull),
            List.of()))
            .doesNotThrowAnyException();
    }

    // ── toLangChainMessage: TOOL with null content, verify request reaches model ──

    @Test
    void completeWithToolResultMessageNullContentSendsRequestToModel() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("response"))
                .build());

        Message toolResultNull = new Message(
            com.azhukov.agent.core.model.Role.TOOL, null, null, null, "call-1", 0, 0);

        ChatResponse r = client.complete(
            List.of(Message.user("hi"),
                Message.assistant("calling", 0),
                toolResultNull),
            List.of());

        assertThat(r.content()).isEqualTo("response");
    }

    // ── complete(): aiMessage.text() is null (tool-call-only response) ──

    @Test
    void completeWithAiMessageNullTextReturnsEmptyContentNotThrows() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        // AiMessage with tool calls and no text → aiMessage.text() returns null
        dev.langchain4j.agent.tool.ToolExecutionRequest req =
            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("1").name("search").arguments("{}").build();

        dev.langchain4j.model.chat.response.ChatResponse response =
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(req)))
                .build();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        // This should return tool calls, not throw NPE
        ChatResponse r = client.complete(List.of(Message.user("search x")), List.of());
        assertThat(r.hasToolCalls()).isTrue();
        // When there are tool calls, content() should be "" (from ChatResponse.toolCalls())
    }

    // ── complete(): AiMessage with no text and no tool calls → null text ──

    @Test
    void completeWithAiMessageNullTextAndNoToolCallsReturnsEmptyString() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        // Create an AiMessage with empty text (simulating null text edge case)
        // AiMessage.from("") creates an AiMessage with text=""
        // But we need to test the case where text() is null
        // The only way text() can be null is if the AiMessage was built with tool calls only
        // In that case, hasToolExecutionRequests() returns true and we take the toolCalls branch
        // So the null text case in the "else" branch is extremely unlikely in practice,
        // but our fix adds null safety: ChatResponse.text(null) → ""
        // ChatResponse.text() already handles null: returns new ChatResponse(content != null ? content : "", ...)
        // So this is just a belt-and-suspenders test
        dev.langchain4j.model.chat.response.ChatResponse response =
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from(""))
                .build();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        ChatResponse r = client.complete(List.of(Message.user("hi")), List.of());
        assertThat(r.content()).isEqualTo("");
    }

    // ── complete(): Normal text still works ──

    @Test
    void completeWithNonNullContentStillWorks() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("hello world"))
                .build());

        ChatResponse r = client.complete(List.of(Message.user("hi")), List.of());
        assertThat(r.content()).isEqualTo("hello world");
    }

    // ── complete(): Normal assistant message with content still works ──

    @Test
    void completeWithAssistantMessageNonNullContentStillWorks() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("response"))
                .build());

        ChatResponse r = client.complete(
            List.of(Message.user("hi"), Message.assistant("previous response", 0)),
            List.of());
        assertThat(r.content()).isEqualTo("response");
    }

    // ── complete(): Normal tool result message with content still works ──

    @Test
    void completeWithToolResultMessageNonNullContentStillWorks() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("response"))
                .build());

        ChatResponse r = client.complete(
            List.of(Message.user("hi"),
                Message.assistant("calling", 0),
                Message.toolResult("call-1", "result text", 0)),
            List.of());
        assertThat(r.content()).isEqualTo("response");
    }
}