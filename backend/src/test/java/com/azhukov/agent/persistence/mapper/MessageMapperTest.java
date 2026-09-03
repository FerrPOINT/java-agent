package com.azhukov.agent.persistence.mapper;

import com.azhukov.agent.core.context.HistorySanitizer;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.persistence.entity.MessageEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MessageMapperTest {

    private final MessageMapper mapper = Mappers.getMapper(MessageMapper.class);

    @Test
    void toDomainMapsAllFields() {
        MessageEntity entity = new MessageEntity();
        entity.setSessionId(UUID.randomUUID());
        entity.setRole("assistant");
        entity.setContent("hello");
        entity.setToolCallId("call-1");
        entity.setToolCallName("weather");
        entity.setToolCallArguments("{\"city\":\"Paris\"}");
        entity.setTurnIndex(3);

        Message message = mapper.toDomain(entity);

        assertThat(message.role()).isEqualTo(Role.ASSISTANT);
        assertThat(message.content()).isEqualTo("hello");
        assertThat(message.toolCall()).isEqualTo(new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}"));
        assertThat(message.toolCalls())
            .containsExactly(new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}"));
        assertThat(message.toolCallId()).isNull();
        assertThat(message.turnIndex()).isEqualTo(3);
    }

    @Test
    void toDomainHandlesToolResult() {
        MessageEntity entity = new MessageEntity();
        entity.setRole("tool");
        entity.setContent("42");
        entity.setToolCallId("call-1");
        entity.setTurnIndex(1);

        Message message = mapper.toDomain(entity);

        assertThat(message.role()).isEqualTo(Role.TOOL);
        assertThat(message.content()).isEqualTo("42");
        assertThat(message.toolCall()).isNull();
        assertThat(message.toolCallId()).isEqualTo("call-1");
    }

    @Test
    void toEntityMapsToolCall() {
        Message message = Message.assistantWithToolCalls("", List.of(new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}")), 1);

        MessageEntity entity = mapper.toEntity(message);

        assertThat(entity.getRole()).isEqualTo("assistant");
        assertThat(entity.getToolCallId()).isEqualTo("call-1");
        assertThat(entity.getToolCallName()).isEqualTo("weather");
        assertThat(entity.getToolCallArguments()).isEqualTo("{\"city\":\"Paris\"}");
        assertThat(entity.getToolCalls())
            .contains("\"id\":\"call-1\"")
            .contains("\"name\":\"weather\"")
            .contains("\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"");
        assertThat(entity.getTurnIndex()).isEqualTo(1);
    }

    @Test
    void toolCallsJsonRoundTripsAllAssistantCalls() {
        List<ToolCall> calls = List.of(
            new ToolCall("call-1", "read_file", "{\"path\":\"a\"}"),
            new ToolCall("call-2", "web_search", "{\"query\":\"b\"}"));
        Message source = Message.assistantWithToolCalls("checking", calls, 4);

        MessageEntity entity = mapper.toEntity(source);
        Message restored = mapper.toDomain(entity);

        assertThat(entity.getToolCallId()).isEqualTo("call-1");
        assertThat(entity.getToolCalls()).contains("\"id\":\"call-2\"");
        assertThat(restored.content()).isEqualTo("checking");
        assertThat(restored.toolCall()).isEqualTo(calls.get(0));
        assertThat(restored.toolCalls()).containsExactlyElementsOf(calls);
        assertThat(restored.turnIndex()).isEqualTo(4);
    }

    @Test
    void toDomainPrefersStoredToolCallsJsonOverLegacyFirstCallColumns() {
        MessageEntity entity = new MessageEntity();
        entity.setRole("assistant");
        entity.setContent("");
        entity.setToolCallId("legacy-call");
        entity.setToolCallName("legacy");
        entity.setToolCallArguments("{}");
        entity.setToolCalls("""
            [
              {"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"a\\"}"}},
              {"id":"call-2","type":"function","function":{"name":"web_search","arguments":"{\\"query\\":\\"b\\"}"}}
            ]
            """);

        Message restored = mapper.toDomain(entity);

        assertThat(restored.toolCalls()).containsExactly(
            new ToolCall("call-1", "read_file", "{\"path\":\"a\"}"),
            new ToolCall("call-2", "web_search", "{\"query\":\"b\"}"));
        assertThat(restored.toolCall()).isEqualTo(restored.toolCalls().get(0));
    }

    @Test
    void toEntityMapsToolResultToolCallId() {
        Message message = Message.toolResult("call-1", "42", 2);

        MessageEntity entity = mapper.toEntity(message);

        assertThat(entity.getRole()).isEqualTo("tool");
        assertThat(entity.getToolCallId()).isEqualTo("call-1");
        assertThat(entity.getContent()).isEqualTo("42");
        assertThat(entity.getTurnIndex()).isEqualTo(2);
    }

    @Test
    void imageCountRoundTrips() {
        Message source = Message.userWithImages("look", 2);

        MessageEntity entity = mapper.toEntity(source);

        assertThat(entity.getImageCount()).isEqualTo(2);

        Message restored = mapper.toDomain(entity);

        assertThat(restored.content()).isEqualTo("look");
        assertThat(restored.imageCount()).isEqualTo(2);
    }

    @Test
    void nullValuesRoundTrip() {
        assertThat(mapper.toDomain(null)).isNull();
        assertThat(mapper.toEntity(null)).isNull();
    }

    @Test
    void toDomainSurfacesAssistantToolCallInList() {
        // Regression (Hermes parity #58168): an assistant row persisted with
        // tool-call metadata must load back with the call in toolCalls (the
        // list). HistorySanitizer Pass 1 matches tool results against that
        // list; a call held only in the singular toolCall field made every
        // DB-loaded tool result look orphaned → dropped → Gemini 400 →
        // CONTEXT_OVERFLOW misclassification → fake compression, lost
        // context, incoherent replies.
        MessageEntity entity = new MessageEntity();
        entity.setRole("assistant");
        entity.setContent("");
        entity.setToolCallId("call-9");
        entity.setToolCallName("session_search");
        entity.setToolCallArguments("{\"query\":\"репозиторий\"}");
        entity.setTurnIndex(1);

        Message domain = mapper.toDomain(entity);

        assertThat(domain.role()).isEqualTo(Role.ASSISTANT);
        assertThat(domain.toolCalls()).isNotNull();
        assertThat(domain.toolCalls()).hasSize(1);
        assertThat(domain.toolCalls().get(0).id()).isEqualTo("call-9");
        assertThat(domain.toolCalls().get(0).name()).isEqualTo("session_search");

        // End-to-end through the sanitizer: the pair must survive intact.
        List<Message> history = List.of(
            Message.user("При чем тут репозиторий?"),
            domain,
            Message.toolResult("call-9", "{\"success\":true}", 1));
        List<Message> sanitized = HistorySanitizer.sanitize(history);
        assertThat(sanitized).hasSize(3);
        assertThat(sanitized.get(2).role()).isEqualTo(Role.TOOL);
    }
}
