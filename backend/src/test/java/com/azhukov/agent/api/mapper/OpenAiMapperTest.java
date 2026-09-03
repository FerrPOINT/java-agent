package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.api.dto.OpenAiChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiMapperTest {

    private final OpenAiMapper mapper = Mappers.getMapper(OpenAiMapper.class);

    @Test
    void toOpenAiMessageMapsAllFields() {
        Message message = Message.assistantWithToolCalls("",
            List.of(new ToolCall("call-1", "search", "{\"q\":\"test\"}")), 1);

        OpenAiChatRequest.OpenAiMessage result = mapper.toOpenAiMessage(message);

        assertThat(result.role()).isEqualTo("assistant");
        assertThat(result.content()).isEqualTo("");
        assertThat(result.toolCalls()).isNotNull();
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).id()).isEqualTo("call-1");
        assertThat(result.toolCalls().get(0).function().name()).isEqualTo("search");
    }

    @Test
    void toMessageMapsSystemRole() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage("system", "You are helpful", null, null);

        Message result = mapper.toMessage(msg);

        assertThat(result.role()).isEqualTo(Role.SYSTEM);
        assertThat(result.content()).isEqualTo("You are helpful");
    }

    @Test
    void toMessagePreservesAssistantToolCallsForReplay() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage(
            "assistant", null,
            List.of(new OpenAiChatRequest.OpenAiToolCall("call-1", "function",
                new OpenAiChatRequest.OpenAiFunctionCall("search", "{\"q\":\"test\"}"))),
            null
        );

        Message result = mapper.toMessage(msg);

        assertThat(result.role()).isEqualTo(Role.ASSISTANT);
        assertThat(result.toolCalls()).containsExactly(new ToolCall("call-1", "search", "{\"q\":\"test\"}"));
    }

    @Test
    void toMessageMapsToolRole() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage("tool", "42", null, "call-1");

        Message result = mapper.toMessage(msg);

        assertThat(result.role()).isEqualTo(Role.TOOL);
        assertThat(result.content()).isEqualTo("42");
        assertThat(result.toolCallId()).isEqualTo("call-1");
    }

    @Test
    void toMessageMapsDefaultToUser() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage("unknown", "hello", null, null);

        Message result = mapper.toMessage(msg);

        assertThat(result.role()).isEqualTo(Role.USER);
        assertThat(result.content()).isEqualTo("hello");
    }

    @Test
    void toMessageFlattensOpenAiTextParts() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage(
            "user",
            List.of(
                Map.of("type", "text", "text", "hello"),
                Map.of("type", "input_text", "text", "world")
            ),
            null,
            null
        );

        Message result = mapper.toMessage(msg);

        assertThat(result.role()).isEqualTo(Role.USER);
        assertThat(result.content()).isEqualTo("hello\nworld");
    }

    @Test
    void toMessageSystemRoleIgnoresImagePartsLikeHermesTextNormalizer() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage(
            "system",
            List.of(
                Map.of("type", "text", "text", "be concise"),
                Map.of("type", "image_url", "image_url", Map.of("url", ""))
            ),
            null,
            null
        );

        Message result = mapper.toMessage(msg);

        assertThat(result.role()).isEqualTo(Role.SYSTEM);
        assertThat(result.content()).isEqualTo("be concise");
        assertThat(result.imageCount()).isZero();
    }

    @Test
    void toMessagePreservesAssistantToolCalls() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage(
            "assistant",
            "",
            List.of(new OpenAiChatRequest.OpenAiToolCall(
                "call-1",
                "function",
                new OpenAiChatRequest.OpenAiFunctionCall("search", "{\"q\":\"test\"}")
            )),
            null
        );

        Message result = mapper.toMessage(msg);

        assertThat(result.role()).isEqualTo(Role.ASSISTANT);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).id()).isEqualTo("call-1");
        assertThat(result.toolCalls().get(0).name()).isEqualTo("search");
        assertThat(result.toolCalls().get(0).arguments()).isEqualTo("{\"q\":\"test\"}");
    }

    @Test
    void toMessageDeterministicallyFillsMissingToolCallIdAndArguments() {
        OpenAiChatRequest.OpenAiMessage msg = new OpenAiChatRequest.OpenAiMessage(
            "assistant",
            "",
            List.of(new OpenAiChatRequest.OpenAiToolCall(
                null,
                "function",
                new OpenAiChatRequest.OpenAiFunctionCall("search", null)
            )),
            null
        );

        Message result = mapper.toMessage(msg);

        assertThat(result.toolCalls()).hasSize(1);
        ToolCall call = result.toolCalls().get(0);
        assertThat(call.id()).isEqualTo(ToolCall.deterministicCallId("search", "{}", 0));
        assertThat(call.arguments()).isEqualTo("{}");
    }

    @Test
    void toMessageHandlesNull() {
        Message result = mapper.toMessage(null);
        assertThat(result.role()).isEqualTo(Role.USER);
    }

    @Test
    void toToolDefinitionMapsAllFields() {
        OpenAiChatRequest.OpenAiTool tool = new OpenAiChatRequest.OpenAiTool(
            "function",
            new OpenAiChatRequest.OpenAiFunction("search", "Search the web", Map.of("type", "object"))
        );

        ToolDefinition result = mapper.toToolDefinition(tool);

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("search");
        assertThat(result.description()).isEqualTo("Search the web");
    }

    @Test
    void toToolDefinitionHandlesNull() {
        assertThat(mapper.toToolDefinition(null)).isNull();
    }

    @Test
    void toOpenAiToolCallMapsAllFields() {
        ToolCall tc = new ToolCall("call-1", "search", "{\"q\":\"test\"}");

        OpenAiChatResponse.ToolCall result = mapper.toOpenAiToolCall(tc);

        assertThat(result.id()).isEqualTo("call-1");
        assertThat(result.type()).isEqualTo("function");
        assertThat(result.function().name()).isEqualTo("search");
        assertThat(result.function().arguments()).isEqualTo("{\"q\":\"test\"}");
    }

    @Test
    void toOpenAiToolCallPreservesId() {
        ToolCall tc = new ToolCall("call-99", "search", "{}");

        OpenAiChatResponse.ToolCall result = mapper.toOpenAiToolCall(tc);

        assertThat(result.id()).isEqualTo("call-99");
        assertThat(result.function().name()).isEqualTo("search");
    }

    @Test
    void toOpenAiResponseWithContentOnly() {
        ChatResponse response = ChatResponse.text("Hello world");

        OpenAiChatResponse result = mapper.toOpenAiResponse("gpt-4", response);

        assertThat(result.model()).isEqualTo("gpt-4");
        assertThat(result.choices()).hasSize(1);
        assertThat(result.choices().get(0).message().content()).isEqualTo("Hello world");
        assertThat(result.choices().get(0).message().toolCalls()).isNull();
        assertThat(result.choices().get(0).finishReason()).isEqualTo("stop");
    }

    @Test
    void toOpenAiResponseWithToolCalls() {
        ChatResponse response = new ChatResponse("",
            List.of(new ToolCall("call-1", "search", "{\"q\":\"test\"}")));

        OpenAiChatResponse result = mapper.toOpenAiResponse("gpt-4", response);

        assertThat(result.choices()).hasSize(1);
        assertThat(result.choices().get(0).message().content()).isNull();
        assertThat(result.choices().get(0).finishReason()).isEqualTo("tool_calls");
        assertThat(result.choices().get(0).message().toolCalls()).hasSize(1);
        assertThat(result.choices().get(0).message().toolCalls().get(0).function().name()).isEqualTo("search");
    }

    @Test
    void toOpenAiResponseWithTextAndToolCallsPreservesContent() {
        ChatResponse response = new ChatResponse("I will search now",
            List.of(new ToolCall("call-1", "search", "{\"q\":\"test\"}")));

        OpenAiChatResponse result = mapper.toOpenAiResponse("gpt-4", response);

        assertThat(result.choices().get(0).message().content()).isEqualTo("I will search now");
        assertThat(result.choices().get(0).finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void roleToStringMapsToLowercase() {
        assertThat(mapper.roleToString(Role.USER)).isEqualTo("user");
        assertThat(mapper.roleToString(Role.ASSISTANT)).isEqualTo("assistant");
        assertThat(mapper.roleToString(Role.SYSTEM)).isEqualTo("system");
        assertThat(mapper.roleToString(Role.TOOL)).isEqualTo("tool");
    }

    @Test
    void roleToStringHandlesNull() {
        assertThat(mapper.roleToString(null)).isEqualTo("user");
    }
}
