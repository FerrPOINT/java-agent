package com.azhukov.agent.persistence.mapper;

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
        assertThat(entity.getTurnIndex()).isEqualTo(1);
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
    void nullValuesRoundTrip() {
        assertThat(mapper.toDomain(null)).isNull();
        assertThat(mapper.toEntity(null)).isNull();
    }
}
