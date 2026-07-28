package com.azhukov.agent.bot.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PairingCodeRepository extends JpaRepository<PairingCodeEntity, java.util.UUID> {

    Optional<PairingCodeEntity> findByCodeAndStatus(String code, String status);

    List<PairingCodeEntity> findByUserIdAndStatus(String userId, String status);

    long countByUserIdAndStatus(String userId, String status);
}