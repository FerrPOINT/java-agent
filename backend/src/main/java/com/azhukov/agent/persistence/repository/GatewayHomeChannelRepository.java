package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.GatewayHomeChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GatewayHomeChannelRepository extends JpaRepository<GatewayHomeChannelEntity, GatewayHomeChannelEntity.GatewayHomeChannelId> {

    Optional<GatewayHomeChannelEntity> findByPlatformAndProfile(String platform, String profile);

    List<GatewayHomeChannelEntity> findAllBy();

    long deleteByPlatformAndProfile(String platform, String profile);
}
