package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.GatewayRuntimeStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayRuntimeStateRepository extends JpaRepository<GatewayRuntimeStateEntity, String> {
}
