package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.core.model.Session;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DomainDtoMapperTest {

    private final DomainDtoMapper mapper = Mappers.getMapper(DomainDtoMapper.class);

    @Test
    void toSessionSummaryDtoMapsAllFields() {
        UUID id = UUID.randomUUID();
        Session session = new Session(id, "u1", "Test Session", "openai", "gpt-4", null, Map.of());

        SessionSummaryDto dto = mapper.toSessionSummaryDto(session);

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.userId()).isEqualTo("u1");
        assertThat(dto.title()).isEqualTo("Test Session");
        assertThat(dto.modelProvider()).isEqualTo("openai");
        assertThat(dto.modelName()).isEqualTo("gpt-4");
    }

    @Test
    void toSessionSummaryDtoHandlesNull() {
        assertThat(mapper.toSessionSummaryDto((Session) null)).isNull();
        assertThat(mapper.toSessionSummaryDto((com.azhukov.agent.persistence.entity.SessionEntity) null)).isNull();
    }

    @Test
    void entityOverloadCarriesTimestampsAndParent() {
        com.azhukov.agent.persistence.entity.SessionEntity e =
            new com.azhukov.agent.persistence.entity.SessionEntity();
        e.setId(UUID.randomUUID());
        e.setUserId("u1");
        e.setTitle("T");
        e.setModelProvider("openai-compatible");
        e.setModelName("app-test");
        e.setCreatedAt(java.time.Instant.parse("2026-08-23T00:00:00Z"));
        e.setUpdatedAt(java.time.Instant.parse("2026-08-23T01:00:00Z"));
        e.setParentSessionId(UUID.randomUUID());
        SessionSummaryDto dto = mapper.toSessionSummaryDto(e);
        assertThat(dto.createdAt()).isEqualTo(java.time.Instant.parse("2026-08-23T00:00:00Z"));
        assertThat(dto.updatedAt()).isEqualTo(java.time.Instant.parse("2026-08-23T01:00:00Z"));
        assertThat(dto.parentSessionId()).isEqualTo(e.getParentSessionId());
    }

    @Test
    void toSessionSummaryDtoListMapsAll() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<Session> sessions = List.of(
            new Session(id1, "u1", "S1", "openai", "gpt-4", null, Map.of()),
            new Session(id2, "u2", "S2", "anthropic", "claude-3", null, Map.of())
        );

        List<SessionSummaryDto> dtos = mapper.toSessionSummaryDtoList(sessions);

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).id()).isEqualTo(id1);
        assertThat(dtos.get(1).id()).isEqualTo(id2);
    }

    @Test
    void toSessionSummaryDtoListHandlesNull() {
        assertThat(mapper.toSessionSummaryDtoList(null)).isEmpty();
    }
}
