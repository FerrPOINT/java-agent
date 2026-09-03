package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.OpenAiResponseConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface OpenAiResponseConversationRepository extends JpaRepository<OpenAiResponseConversationEntity, String> {

    Optional<OpenAiResponseConversationEntity> findByName(String name);

    void deleteByResponseId(String responseId);

    void deleteByResponseIdIn(Collection<String> responseIds);
}
