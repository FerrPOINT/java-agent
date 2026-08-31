package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.UserApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserApiKeyRepository extends JpaRepository<UserApiKeyEntity, UUID> {

    Optional<UserApiKeyEntity> findByKeyHash(String keyHash);
}