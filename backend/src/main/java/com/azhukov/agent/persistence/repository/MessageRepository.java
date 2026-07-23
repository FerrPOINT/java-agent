package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    List<MessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
