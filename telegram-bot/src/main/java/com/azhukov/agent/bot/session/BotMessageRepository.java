package com.azhukov.agent.bot.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BotMessageRepository extends JpaRepository<BotMessageEntity, UUID> {

    List<BotMessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<BotMessageEntity> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    long countBySessionId(UUID sessionId);
}