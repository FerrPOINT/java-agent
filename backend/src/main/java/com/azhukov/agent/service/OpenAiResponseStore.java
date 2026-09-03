package com.azhukov.agent.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.OpenAiResponseConversationEntity;
import com.azhukov.agent.persistence.entity.OpenAiResponseEntity;
import com.azhukov.agent.persistence.repository.OpenAiResponseConversationRepository;
import com.azhukov.agent.persistence.repository.OpenAiResponseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiResponseStore {

    private static final int MAX_STORED_RESPONSES = 100;
    private static final TypeReference<Map<String, Object>> RESPONSE_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Message>> HISTORY_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final OpenAiResponseRepository responseRepository;
    private final OpenAiResponseConversationRepository conversationRepository;

    private final ConcurrentMap<String, String> conversationIndex = new ConcurrentHashMap<>();
    private final Map<String, StoredResponse> responseStore = new LinkedHashMap<>(16, 0.75f, true);

    @Transactional
    public StoredResponse get(String responseId) {
        String normalizedId = normalize(responseId);
        if (normalizedId == null) {
            return null;
        }
        if (persistentEnabled()) {
            return getPersistent(normalizedId);
        }
        synchronized (responseStore) {
            return copy(responseStore.get(normalizedId));
        }
    }

    @Transactional
    public void put(String responseId, StoredResponse response, String conversation) {
        String normalizedId = normalize(responseId);
        if (normalizedId == null || response == null) {
            return;
        }
        if (persistentEnabled()) {
            putPersistent(normalizedId, response, conversation);
            return;
        }
        synchronized (responseStore) {
            responseStore.put(normalizedId, copy(response));
            evictOldResponses();
        }
        if (conversation != null && !conversation.isBlank()) {
            conversationIndex.put(conversation.trim(), normalizedId);
        }
    }

    @Transactional
    public boolean delete(String responseId) {
        String normalizedId = normalize(responseId);
        if (normalizedId == null) {
            return false;
        }
        if (persistentEnabled()) {
            boolean exists = responseRepository.existsById(normalizedId);
            if (!exists) {
                return false;
            }
            conversationRepository.deleteByResponseId(normalizedId);
            responseRepository.deleteById(normalizedId);
            return true;
        }
        StoredResponse removed;
        synchronized (responseStore) {
            removed = responseStore.remove(normalizedId);
        }
        if (removed == null) {
            return false;
        }
        conversationIndex.entrySet().removeIf(entry -> normalizedId.equals(entry.getValue()));
        return true;
    }

    @Transactional(readOnly = true)
    public String previousResponseId(String previousResponseId, String conversation) {
        if (previousResponseId != null && !previousResponseId.isBlank()) {
            return previousResponseId.trim();
        }
        if (conversation != null && !conversation.isBlank()) {
            if (persistentEnabled()) {
                return conversationRepository.findByName(conversation.trim())
                    .map(OpenAiResponseConversationEntity::getResponseId)
                    .orElse(null);
            }
            return conversationIndex.get(conversation.trim());
        }
        return null;
    }

    private StoredResponse getPersistent(String responseId) {
        OpenAiResponseEntity entity = responseRepository.findById(responseId).orElse(null);
        if (entity == null) {
            return null;
        }
        entity.setAccessedAt(Instant.now());
        responseRepository.save(entity);
        try {
            return deserialize(entity);
        } catch (RuntimeException e) {
            log.warn("Corrupted OpenAI response store entry {}; evicting it", responseId, e);
            conversationRepository.deleteByResponseId(responseId);
            responseRepository.deleteById(responseId);
            return null;
        }
    }

    private void putPersistent(String responseId, StoredResponse response, String conversation) {
        OpenAiResponseEntity entity = responseRepository.findById(responseId).orElseGet(OpenAiResponseEntity::new);
        entity.setResponseId(responseId);
        try {
            entity.setResponseJson(objectMapper.writeValueAsString(response.response()));
            entity.setConversationHistoryJson(objectMapper.writeValueAsString(response.conversationHistory()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize OpenAI response state", e);
        }
        entity.setInstructions(response.instructions());
        entity.setSessionId(response.sessionId());
        entity.setAccessedAt(Instant.now());
        responseRepository.save(entity);
        evictOldPersistentResponses();

        if (conversation != null && !conversation.isBlank()) {
            OpenAiResponseConversationEntity conversationEntity = new OpenAiResponseConversationEntity();
            conversationEntity.setName(conversation.trim());
            conversationEntity.setResponseId(responseId);
            conversationRepository.save(conversationEntity);
        }
    }

    private StoredResponse deserialize(OpenAiResponseEntity entity) {
        try {
            Map<String, Object> response = objectMapper.readValue(entity.getResponseJson(), RESPONSE_TYPE);
            List<Message> history = objectMapper.readValue(entity.getConversationHistoryJson(), HISTORY_TYPE);
            return new StoredResponse(
                response != null ? response : Map.of(),
                history != null ? List.copyOf(history) : List.of(),
                entity.getInstructions(),
                entity.getSessionId()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialize OpenAI response state", e);
        }
    }

    private void evictOldResponses() {
        Iterator<String> ids = responseStore.keySet().iterator();
        while (responseStore.size() > MAX_STORED_RESPONSES && ids.hasNext()) {
            String evictedId = ids.next();
            ids.remove();
            conversationIndex.entrySet().removeIf(entry -> evictedId.equals(entry.getValue()));
        }
    }

    private void evictOldPersistentResponses() {
        long count = responseRepository.count();
        long excess = count - MAX_STORED_RESPONSES;
        if (excess <= 0) {
            return;
        }
        int limit = excess > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) excess;
        List<String> evictedIds = responseRepository.findOldestResponseIds(PageRequest.of(0, limit));
        if (evictedIds.isEmpty()) {
            return;
        }
        conversationRepository.deleteByResponseIdIn(evictedIds);
        responseRepository.deleteAllById(evictedIds);
    }

    private boolean persistentEnabled() {
        return objectMapper != null && responseRepository != null && conversationRepository != null;
    }

    private static String normalize(String responseId) {
        if (responseId == null || responseId.isBlank()) {
            return null;
        }
        return responseId.trim();
    }

    private StoredResponse copy(StoredResponse response) {
        if (response == null) {
            return null;
        }
        return new StoredResponse(
            copyResponse(response.response()),
            response.conversationHistory() != null
                ? List.copyOf(response.conversationHistory())
                : List.of(),
            response.instructions(),
            response.sessionId()
        );
    }

    private Map<String, Object> copyResponse(Map<String, Object> response) {
        if (response == null || response.isEmpty()) {
            return Map.of();
        }
        if (objectMapper != null) {
            try {
                return objectMapper.readValue(objectMapper.writeValueAsString(response), RESPONSE_TYPE);
            } catch (JsonProcessingException e) {
                log.debug("Falling back to shallow OpenAI response copy", e);
            }
        }
        return new LinkedHashMap<>(response);
    }

    public record StoredResponse(
        Map<String, Object> response,
        List<Message> conversationHistory,
        String instructions,
        UUID sessionId
    ) {}
}
