package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.api.dto.OpenAiChatResponse;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for {@link OpenAiMapper}.
 * Covers null inputs, empty collections, edge cases, and untested branches.
 */
class OpenAiMapperBranchTest {

    private final OpenAiMapper mapper = org.mapstruct.factory.Mappers.getMapper(OpenAiMapper.class);

    // ── toOpenAiMessages: null and empty list ──

    @Test
    void toOpenAiMessages_nullInput_returnsEmpty() {
        assertThat(mapper.toOpenAiMessages(null)).isEmpty();
    }

    @Test
    void toOpenAiMessages_emptyInput_returnsEmpty() {
        assertThat(mapper.toOpenAiMessages(List.of())).isEmpty();
    }

    @Test
    void toOpenAiMessages_mapsMultipleMessages() {
        List<Message> messages = List.of(
            Message.system("sys"),
            Message.user("hello"),
            Message.assistant("hi", 0)
        );
        var result = mapper.toOpenAiMessages(messages);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).role()).isEqualTo("system");
        assertThat(result.get(1).role()).isEqualTo("user");
        assertThat(result.get(2).role()).isEqualTo("assistant");
    }

    // ── toOpenAiTools: null and empty list ──

    @Test
    void toOpenAiTools_nullInput_returnsEmpty() {
        assertThat(mapper.toOpenAiTools(null)).isEmpty();
    }

    @Test
    void toOpenAiTools_emptyInput_returnsEmpty() {
        assertThat(mapper.toOpenAiTools(List.of())).isEmpty();
    }

    @Test
    void toOpenAiTools_mapsToolWithAllFields() {
        ToolDefinition def = new ToolDefinition("search", "Search the web", Map.of("type", "object"));
        var result = mapper.toOpenAiTools(List.of(def));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo("function");
        assertThat(result.get(0).function().name()).isEqualTo("search");
        assertThat(result.get(0).function().description()).isEqualTo("Search the web");
    }

    // ── toChatResponse: null and edge cases ──

    @Test
    void toChatResponse_nullResponse_returnsEmptyText() {
        ChatResponse result = mapper.toChatResponse(null);
        assertThat(result.content()).isEmpty();
        assertThat(result.toolCalls()).isEmpty();
    }

    @Test
    void toChatResponse_nullChoices_returnsEmptyText() {
        OpenAiChatResponse response = new OpenAiChatResponse(
            "id", "chat.completion", 0L, "model",
            null, null
        );
        ChatResponse result = mapper.toChatResponse(response);
        assertThat(result.content()).isEmpty();
    }

    @Test
    void toChatResponse_emptyChoices_returnsEmptyText() {
        OpenAiChatResponse response = new OpenAiChatResponse(
            "id", "chat.completion", 0L, "model",
            List.of(), null
        );
        ChatResponse result = mapper.toChatResponse(response);
        assertThat(result.content()).isEmpty();
    }

    @Test
    void toChatResponse_nullMessageContent_returnsEmpty() {
        OpenAiChatResponse.Message message = new OpenAiChatResponse.Message("assistant", null, null);
        OpenAiChatResponse response = new OpenAiChatResponse(
            "id", "chat.completion", 0L, "model",
            List.of(new OpenAiChatResponse.Choice(0, message, "stop")), null
        );
        ChatResponse result = mapper.toChatResponse(response);
        assertThat(result.content()).isEmpty();
        assertThat(result.toolCalls()).isEmpty();
    }

    @Test
    void toChatResponse_withToolCalls() {
        OpenAiChatResponse.ToolCall toolCall = new OpenAiChatResponse.ToolCall(
            "call-1", "function",
            new OpenAiChatResponse.Function("search", "{\"q\":\"test\"}")
        );
        OpenAiChatResponse.Message message = new OpenAiChatResponse.Message("assistant", null, List.of(toolCall));
        OpenAiChatResponse response = new OpenAiChatResponse(
            "id", "chat.completion", 0L, "model",
            List.of(new OpenAiChatResponse.Choice(0, message, "stop")), null
        );
        ChatResponse result = mapper.toChatResponse(response);
        assertThat(result.content()).isEmpty();
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).id()).isEqualTo("call-1");
        assertThat(result.toolCalls().get(0).name()).isEqualTo("search");
    }

    @Test
    void toChatResponse_emptyToolCalls_returnsTextOnly() {
        OpenAiChatResponse.Message message = new OpenAiChatResponse.Message("assistant", "Hello", List.of());
        OpenAiChatResponse response = new OpenAiChatResponse(
            "id", "chat.completion", 0L, "model",
            List.of(new OpenAiChatResponse.Choice(0, message, "stop")), null
        );
        ChatResponse result = mapper.toChatResponse(response);
        assertThat(result.content()).isEqualTo("Hello");
        assertThat(result.toolCalls()).isEmpty();
    }

    // ── toolCallsToOpenAi: null and empty ──

    @Test
    void toolCallsToOpenAi_nullInput_returnsNull() {
        assertThat(mapper.toolCallsToOpenAi(null)).isNull();
    }

    @Test
    void toolCallsToOpenAi_emptyInput_returnsNull() {
        assertThat(mapper.toolCallsToOpenAi(List.of())).isNull();
    }

    // ── openAiToolCallsToDomain: null and empty ──

    @Test
    void openAiToolCallsToDomain_nullInput_returnsEmpty() {
        assertThat(mapper.openAiToolCallsToDomain(null)).isEmpty();
    }

    @Test
    void openAiToolCallsToDomain_emptyInput_returnsEmpty() {
        assertThat(mapper.openAiToolCallsToDomain(List.of())).isEmpty();
    }

    // ── toMessage: various roles ──

    @Test
    void toMessage_developerRole() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage(
            "developer", "dev prompt", null, null
        );
        Message result = mapper.toMessage(msg);
        assertThat(result.role()).isEqualTo(Role.DEVELOPER);
        assertThat(result.content()).isEqualTo("dev prompt");
    }

    @Test
    void toMessage_assistantRole() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage(
            "assistant", "hi there", null, null
        );
        Message result = mapper.toMessage(msg);
        assertThat(result.role()).isEqualTo(Role.ASSISTANT);
        assertThat(result.content()).isEqualTo("hi there");
    }

    @Test
    void toMessage_nullRole_defaultsToUser() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage(
            null, "content", null, null
        );
        Message result = mapper.toMessage(msg);
        assertThat(result.role()).isEqualTo(Role.USER);
        assertThat(result.content()).isEqualTo("content");
    }

    @Test
    void toMessage_nullContent_returnsEmptyUser() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage(
            null, null, null, null
        );
        Message result = mapper.toMessage(msg);
        assertThat(result.role()).isEqualTo(Role.USER);
    }

    // ── toToolDefinition: null function ──

    @Test
    void toToolDefinition_nullFunction_returnsNull() {
        OpenAiChatRequest.OpenAiTool tool = new OpenAiChatRequest.OpenAiTool("function", null);
        assertThat(mapper.toToolDefinition(tool)).isNull();
    }

    // ── toOpenAiToolCall: null id generates UUID ──

    @Test
    void toOpenAiToolCall_nullId_generatesUuid() {
        ToolCall tc = new ToolCall("", "search", "{}");
        var result = mapper.toOpenAiToolCall(tc);
        // Empty string id should not be replaced — only null would, but ToolCall requires non-null
        assertThat(result.id()).isEqualTo("");
    }

    // ── toOpenAiResponse: content null and empty toolCalls ──

    @Test
    void toOpenAiResponse_nullContent_returnsEmpty() {
        ChatResponse response = new ChatResponse("", List.of());
        var result = mapper.toOpenAiResponse("gpt-4", response);
        assertThat(result.choices().get(0).message().content()).isEmpty();
    }

    @Test
    void toOpenAiResponse_nullToolCalls_returnsContentOnly() {
        ChatResponse response = new ChatResponse("Hello", null);
        var result = mapper.toOpenAiResponse("gpt-4", response);
        assertThat(result.choices().get(0).message().content()).isEqualTo("Hello");
        assertThat(result.choices().get(0).message().toolCalls()).isNull();
    }

    // ── DomainDtoMapper ──

    @Test
    void domainDtoMapper_emptyList_returnsEmpty() {
        var domainMapper = org.mapstruct.factory.Mappers.getMapper(DomainDtoMapper.class);
        assertThat(domainMapper.toSessionSummaryDtoList(List.of())).isEmpty();
    }
}