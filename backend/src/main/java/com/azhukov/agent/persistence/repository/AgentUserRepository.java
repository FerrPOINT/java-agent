package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.AgentUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentUserRepository extends JpaRepository<AgentUserEntity, String> {

    Optional<AgentUserEntity> findByUsername(String username);
}