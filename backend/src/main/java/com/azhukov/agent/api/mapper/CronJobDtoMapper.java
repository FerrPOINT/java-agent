package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.CronExecutionLogDto;
import com.azhukov.agent.api.dto.CronJobDto;
import com.azhukov.agent.persistence.entity.CronExecutionLogEntity;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * Maps cron persistence entities to API DTOs (entity → DTO, one-way).
 * Hides {@link CronJobEntity} / {@link CronExecutionLogEntity} from the controller layer.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class, componentModel = "spring")
public interface CronJobDtoMapper {

    CronJobDto toDto(CronJobEntity entity);

    List<CronJobDto> toDtoList(List<CronJobEntity> entities);

    CronExecutionLogDto toExecutionLogDto(CronExecutionLogEntity entity);

    List<CronExecutionLogDto> toExecutionLogDtoList(List<CronExecutionLogEntity> entities);
}