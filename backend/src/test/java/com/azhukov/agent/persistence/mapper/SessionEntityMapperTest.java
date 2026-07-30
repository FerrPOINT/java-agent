package com.azhukov.agent.persistence.mapper;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.SessionEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionEntityMapperTest {

    private final SessionEntityMapper mapper = Mappers.getMapper(SessionEntityMapper.class);

    @Test
    void toDomainMapsAllFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        SessionEntity entity = new SessionEntity();
        entity.setId(id);
        entity.setUserId("u1");
        entity.setTitle("title");
        entity.setModelProvider("openai");
        entity.setModelName("gpt-4");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        Session session = mapper.toDomain(entity);

        assertThat(session.id()).isEqualTo(id);
        assertThat(session.userId()).isEqualTo("u1");
        assertThat(session.title()).isEqualTo("title");
        assertThat(session.modelProvider()).isEqualTo("openai");
        assertThat(session.modelName()).isEqualTo("gpt-4");
    }

    @Test
    void toEntityMapsAllFields() {
        UUID id = UUID.randomUUID();
        Session session = new Session(id, "u1", "title", "openai", "gpt-4", null, Map.of());

        SessionEntity entity = mapper.toEntity(session);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getUserId()).isEqualTo("u1");
        assertThat(entity.getTitle()).isEqualTo("title");
        assertThat(entity.getModelProvider()).isEqualTo("openai");
        assertThat(entity.getModelName()).isEqualTo("gpt-4");
    }

    @Test
    void nullValuesRoundTrip() {
        assertThat(mapper.toDomain(null)).isNull();
        assertThat(mapper.toEntity(null)).isNull();
    }
}
