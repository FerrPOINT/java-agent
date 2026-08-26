package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.CheckpointDto;
import com.azhukov.agent.persistence.entity.CheckpointEntity;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * Maps {@link CheckpointEntity} to {@link CheckpointDto} (entity → DTO, one-way).
 * Hides the JPA entity from the controller layer.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class, componentModel = "spring")
public interface CheckpointDtoMapper {

    CheckpointDto toDto(CheckpointEntity entity);

    List<CheckpointDto> toDtoList(List<CheckpointEntity> entities);
}