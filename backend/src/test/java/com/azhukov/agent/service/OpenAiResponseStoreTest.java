package com.azhukov.agent.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.OpenAiResponseConversationEntity;
import com.azhukov.agent.persistence.entity.OpenAiResponseEntity;
import com.azhukov.agent.persistence.repository.OpenAiResponseConversationRepository;
import com.azhukov.agent.persistence.repository.OpenAiResponseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiResponseStoreTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ObjectMapper objectMapper;
    private OpenAiResponseStore store;

    @Mock
    private OpenAiResponseRepository responseRepository;

    @Mock
    private OpenAiResponseConversationRepository conversationRepository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        store = new OpenAiResponseStore(objectMapper, responseRepository, conversationRepository);
    }

    @Test
    void putPersistsResponseConversationAndEvictsOldestLikeHermes() throws Exception {
        when(responseRepository.findById("resp_new")).thenReturn(Optional.empty());
        when(responseRepository.count()).thenReturn(101L);
        when(responseRepository.findOldestResponseIds(any(Pageable.class))).thenReturn(List.of("resp_old"));

        OpenAiResponseStore.StoredResponse response = new OpenAiResponseStore.StoredResponse(
            Map.of("id", "resp_new", "object", "response"),
            List.of(Message.user("hello")),
            "be concise",
            SESSION_ID
        );

        store.put(" resp_new ", response, " chat-a ");

        ArgumentCaptor<OpenAiResponseEntity> responseCaptor = ArgumentCaptor.forClass(OpenAiResponseEntity.class);
        verify(responseRepository).save(responseCaptor.capture());
        OpenAiResponseEntity savedResponse = responseCaptor.getValue();
        assertThat(savedResponse.getResponseId()).isEqualTo("resp_new");
        assertThat(objectMapper.readTree(savedResponse.getResponseJson()).get("id").asText()).isEqualTo("resp_new");
        assertThat(objectMapper.readTree(savedResponse.getConversationHistoryJson()).get(0).get("content").asText())
            .isEqualTo("hello");
        assertThat(savedResponse.getInstructions()).isEqualTo("be concise");
        assertThat(savedResponse.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(savedResponse.getAccessedAt()).isNotNull();

        ArgumentCaptor<OpenAiResponseConversationEntity> conversationCaptor =
            ArgumentCaptor.forClass(OpenAiResponseConversationEntity.class);
        verify(conversationRepository).save(conversationCaptor.capture());
        assertThat(conversationCaptor.getValue().getName()).isEqualTo("chat-a");
        assertThat(conversationCaptor.getValue().getResponseId()).isEqualTo("resp_new");
        verify(conversationRepository).deleteByResponseIdIn(List.of("resp_old"));
        verify(responseRepository).deleteAllById(List.of("resp_old"));
    }

    @Test
    void getRefreshesAccessTimeAndDeserializesPersistedStateLikeHermes() throws Exception {
        OpenAiResponseEntity entity = new OpenAiResponseEntity();
        entity.setResponseId("resp_saved");
        entity.setResponseJson(objectMapper.writeValueAsString(Map.of("id", "resp_saved")));
        entity.setConversationHistoryJson(objectMapper.writeValueAsString(List.of(Message.user("stored"))));
        entity.setInstructions("instructions");
        entity.setSessionId(SESSION_ID);
        entity.setAccessedAt(Instant.EPOCH);
        when(responseRepository.findById("resp_saved")).thenReturn(Optional.of(entity));

        OpenAiResponseStore.StoredResponse restored = store.get(" resp_saved ");

        assertThat(restored).isNotNull();
        assertThat(restored.response()).containsEntry("id", "resp_saved");
        assertThat(restored.conversationHistory()).hasSize(1);
        assertThat(restored.conversationHistory().get(0).content()).isEqualTo("stored");
        assertThat(restored.instructions()).isEqualTo("instructions");
        assertThat(restored.sessionId()).isEqualTo(SESSION_ID);
        assertThat(entity.getAccessedAt()).isAfter(Instant.EPOCH);
        verify(responseRepository).save(entity);
    }

    @Test
    void getEvictsCorruptedPersistentJsonLikeHermes() {
        OpenAiResponseEntity entity = new OpenAiResponseEntity();
        entity.setResponseId("resp_bad");
        entity.setResponseJson("{");
        entity.setConversationHistoryJson("[]");
        entity.setAccessedAt(Instant.EPOCH);
        when(responseRepository.findById("resp_bad")).thenReturn(Optional.of(entity));

        assertThat(store.get("resp_bad")).isNull();

        verify(conversationRepository).deleteByResponseId("resp_bad");
        verify(responseRepository).deleteById("resp_bad");
    }

    @Test
    void previousResponseIdResolvesPersistentConversationAliasLikeHermes() {
        OpenAiResponseConversationEntity conversation = new OpenAiResponseConversationEntity();
        conversation.setName("chat-a");
        conversation.setResponseId("resp_latest");
        when(conversationRepository.findByName("chat-a")).thenReturn(Optional.of(conversation));

        assertThat(store.previousResponseId(null, " chat-a ")).isEqualTo("resp_latest");
        assertThat(store.previousResponseId(" resp_explicit ", " chat-a ")).isEqualTo("resp_explicit");
    }
}
