package com.azhukov.agent.service;

import com.azhukov.agent.api.AgentException;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiSessionServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String MODEL = "gpt-test";

    @Mock
    private AgentSessionResolver sessionResolver;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AgentProperties properties;
    private OpenAiSessionService service;
    private Session session;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        MessageMapper messageMapper = Mappers.getMapper(MessageMapper.class);
        service = new OpenAiSessionService(
            sessionResolver,
            sessionRepository,
            messageRepository,
            messageMapper,
            transactionTemplate,
            properties
        );
        session = new Session(SESSION_ID, AgentProperties.DEFAULT_USER_ID, "OpenAI",
            "openai-compatible", MODEL, null, Map.of(), null);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void resolveWithoutHeadersCreatesApiServerSession() {
        when(sessionResolver.createSession(AgentProperties.DEFAULT_USER_ID, "openai-compatible", MODEL, "api_server"))
            .thenReturn(session);

        OpenAiSessionService.OpenAiSessionContext context = service.resolve(null, null, MODEL);

        assertThat(context.session()).isEqualTo(session);
        assertThat(context.continuationRequested()).isFalse();
        assertThat(context.sessionKey()).isNull();
    }

    @Test
    void resolveWithoutHeadersDoesNotPersistVirtualModelAliasLikeHermes() {
        Session aliaslessSession = new Session(SESSION_ID, AgentProperties.DEFAULT_USER_ID, "OpenAI",
            "openai-compatible", "", null, Map.of(), null);
        when(sessionResolver.createSession(AgentProperties.DEFAULT_USER_ID, "openai-compatible", "", "api_server"))
            .thenReturn(aliaslessSession);

        OpenAiSessionService.OpenAiSessionContext context = service.resolve(null, null, "hermes-agent");

        assertThat(context.session()).isEqualTo(aliaslessSession);
        verify(sessionResolver)
            .createSession(AgentProperties.DEFAULT_USER_ID, "openai-compatible", "", "api_server");
    }

    @Test
    void resolveWithoutHeadersDoesNotPersistProviderPrefixedVirtualAliasLikeHermes() {
        Session aliaslessSession = new Session(SESSION_ID, AgentProperties.DEFAULT_USER_ID, "OpenAI",
            "openai-compatible", "", null, Map.of(), null);
        when(sessionResolver.createSession(AgentProperties.DEFAULT_USER_ID, "openai-compatible", "", "api_server"))
            .thenReturn(aliaslessSession);

        OpenAiSessionService.OpenAiSessionContext context =
            service.resolve(null, null, "openrouter::hermes-agent");

        assertThat(context.session()).isEqualTo(aliaslessSession);
        verify(sessionResolver)
            .createSession(AgentProperties.DEFAULT_USER_ID, "openai-compatible", "", "api_server");
    }

    @Test
    void resolveChatCompletionsWithoutHeaderUsesDeterministicStatelessSessionLikeHermes() {
        String seed = "system prompt\nfirst user";
        UUID derived = OpenAiSessionService.deterministicChatSessionUuid(AgentProperties.DEFAULT_USER_ID, seed);
        when(sessionResolver.loadOrCreateSession(
            derived,
            AgentProperties.DEFAULT_USER_ID,
            "openai-compatible",
            MODEL,
            "api_server"))
            .thenReturn(session);

        OpenAiSessionService.OpenAiSessionContext context =
            service.resolveChatCompletions(null, null, MODEL, seed);

        assertThat(context.session()).isEqualTo(session);
        assertThat(context.continuationRequested()).isFalse();
        assertThat(context.sessionKey()).isNull();
    }

    @Test
    void resolveChatCompletionsDeterministicSeedIsScopedBySessionKey() {
        properties.getSecurity().setApiKey("secret");
        String sessionKey = "agent:main:webui:42";
        String seed = "system prompt\nfirst user";
        UUID derived = OpenAiSessionService.deterministicChatSessionUuid(sessionKey, seed);
        Session keyedSession = new Session(derived, sessionKey, "OpenAI",
            "openai-compatible", MODEL, null, Map.of(), null);
        when(sessionResolver.loadOrCreateSession(
            derived,
            sessionKey,
            "openai-compatible",
            MODEL,
            "api_server"))
            .thenReturn(keyedSession);

        OpenAiSessionService.OpenAiSessionContext context =
            service.resolveChatCompletions(null, sessionKey, MODEL, seed);

        assertThat(context.session()).isEqualTo(keyedSession);
        assertThat(context.continuationRequested()).isFalse();
        assertThat(context.sessionKey()).isEqualTo(sessionKey);
    }

    @Test
    void resolveWithSessionKeyRequiresConfiguredApiKey() {
        assertThatThrownBy(() -> service.resolve(null, "agent:main:webui:42", MODEL))
            .isInstanceOf(AgentException.class)
            .hasMessage("X-Hermes-Session-Key requires API key authentication. "
                + "Configure API_SERVER_KEY to enable this feature.")
            .extracting(ex -> ((AgentException) ex).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void resolveWithContinuationHeaderRequiresConfiguredApiKeyLikeHermes() {
        assertThatThrownBy(() -> service.resolve(SESSION_ID.toString(), null, MODEL))
            .isInstanceOf(AgentException.class)
            .hasMessage("Session continuation requires API key authentication. "
                + "Configure API_SERVER_KEY to enable this feature.")
            .extracting(ex -> ((AgentException) ex).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void resolveWithSessionKeyUsesItAsUserScopeForNewSessions() {
        properties.getSecurity().setApiKey("secret");
        String sessionKey = "agent:main:webui:42";
        Session keyedSession = new Session(SESSION_ID, sessionKey, "OpenAI",
            "openai-compatible", MODEL, null, Map.of(), null);
        when(sessionResolver.createSession(sessionKey, "openai-compatible", MODEL, "api_server"))
            .thenReturn(keyedSession);

        OpenAiSessionService.OpenAiSessionContext context = service.resolve(null, sessionKey, MODEL);

        assertThat(context.session()).isEqualTo(keyedSession);
        assertThat(context.sessionKey()).isEqualTo(sessionKey);
    }

    @Test
    void resolveWithContinuationHeaderLoadsExistingSession() {
        properties.getSecurity().setApiKey("secret");
        when(sessionResolver.resolveResumeSessionId(SESSION_ID)).thenReturn(SESSION_ID);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(sessionResolver.loadSession(SESSION_ID)).thenReturn(session);

        OpenAiSessionService.OpenAiSessionContext context = service.resolve(SESSION_ID.toString(), null, MODEL);

        assertThat(context.session()).isEqualTo(session);
        assertThat(context.continuationRequested()).isTrue();
    }

    @Test
    void resolveWithStringContinuationHeaderMapsToStableInternalSessionLikeHermes() {
        properties.getSecurity().setApiKey("secret");
        String externalSessionId = "api-not-a-uuid";
        UUID mappedSessionId = OpenAiSessionService.deterministicExternalSessionUuid(
            AgentProperties.DEFAULT_USER_ID,
            externalSessionId
        );
        Session mappedSession = new Session(mappedSessionId, AgentProperties.DEFAULT_USER_ID, "OpenAI",
            "openai-compatible", MODEL, null, Map.of(), null);
        when(sessionResolver.loadOrCreateSession(
            mappedSessionId,
            AgentProperties.DEFAULT_USER_ID,
            "openai-compatible",
            MODEL,
            "api_server"))
            .thenReturn(mappedSession);

        OpenAiSessionService.OpenAiSessionContext context = service.resolve(externalSessionId, null, MODEL);

        assertThat(context.session()).isEqualTo(mappedSession);
        assertThat(context.continuationRequested()).isTrue();
        assertThat(context.externalSessionId()).isEqualTo(externalSessionId);
        assertThat(context.responseSessionId()).isEqualTo(externalSessionId);
    }

    @Test
    void stringContinuationHeaderIsScopedBySessionKeyLikeHermes() {
        properties.getSecurity().setApiKey("secret");
        String sessionKey = "agent:main:webui:42";
        String externalSessionId = "session_alice_1";
        UUID defaultMappedId = OpenAiSessionService.deterministicExternalSessionUuid(
            AgentProperties.DEFAULT_USER_ID,
            externalSessionId
        );
        UUID keyedMappedId = OpenAiSessionService.deterministicExternalSessionUuid(sessionKey, externalSessionId);
        Session keyedSession = new Session(keyedMappedId, sessionKey, "OpenAI",
            "openai-compatible", MODEL, null, Map.of(), null);
        when(sessionResolver.loadOrCreateSession(
            keyedMappedId,
            sessionKey,
            "openai-compatible",
            MODEL,
            "api_server"))
            .thenReturn(keyedSession);

        OpenAiSessionService.OpenAiSessionContext context = service.resolve(externalSessionId, sessionKey, MODEL);

        assertThat(context.session()).isEqualTo(keyedSession);
        assertThat(context.session().id()).isNotEqualTo(defaultMappedId);
        assertThat(context.sessionKey()).isEqualTo(sessionKey);
        assertThat(context.responseSessionId()).isEqualTo(externalSessionId);
    }

    @Test
    void resolveRejectsControlCharactersInContinuationId() {
        properties.getSecurity().setApiKey("secret");

        assertThatThrownBy(() -> service.resolve("api\nnot-safe", null, MODEL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid session ID");
    }

    @Test
    void resolveRunSessionWithStringBodyIdMapsWithoutContinuationHeaderAuthLikeHermes() {
        String externalSessionId = "cron_job42_20260801_090000";
        UUID mappedSessionId = OpenAiSessionService.deterministicExternalSessionUuid(
            AgentProperties.DEFAULT_USER_ID,
            externalSessionId
        );
        Session mappedSession = new Session(mappedSessionId, AgentProperties.DEFAULT_USER_ID, "OpenAI",
            "openai-compatible", MODEL, null, Map.of(), null);
        when(sessionResolver.loadOrCreateSession(
            mappedSessionId,
            AgentProperties.DEFAULT_USER_ID,
            "openai-compatible",
            MODEL,
            "api_server"))
            .thenReturn(mappedSession);

        OpenAiSessionService.OpenAiSessionContext context =
            service.resolveRunSession(externalSessionId, null, MODEL);

        assertThat(context.session()).isEqualTo(mappedSession);
        assertThat(context.continuationRequested()).isTrue();
        assertThat(context.responseSessionId()).isEqualTo(externalSessionId);
    }

    @Test
    void persistTurnSavesIncomingAssistantAndUpdatesSessionStats() {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(messageRepository.countBySessionId(SESSION_ID)).thenReturn(2L);
        OpenAiSessionService.OpenAiSessionContext context =
            new OpenAiSessionService.OpenAiSessionContext(session, false, null);

        service.persistTurn(context, List.of(Message.user("question")), ChatResponse.text("answer"));

        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(MessageEntity::getRole)
            .containsExactly("user", "assistant");
        assertThat(captor.getAllValues()).extracting(MessageEntity::getContent)
            .containsExactly("question", "answer");
        verify(sessionRepository).updateLastActiveAndMessageCount(
            org.mockito.ArgumentMatchers.eq(SESSION_ID),
            any(),
            org.mockito.ArgumentMatchers.eq(2)
        );
        verify(sessionRepository).touchUpdatedAt(org.mockito.ArgumentMatchers.eq(SESSION_ID), any());
        verify(sessionRepository).updatePreview(SESSION_ID, "question");
    }

    @Test
    void persistTurnPreservesAssistantToolCalls() {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(messageRepository.countBySessionId(SESSION_ID)).thenReturn(1L);
        OpenAiSessionService.OpenAiSessionContext context =
            new OpenAiSessionService.OpenAiSessionContext(session, false, null);

        service.persistTurn(context, List.of(),
            ChatResponse.toolCalls(List.of(
                new ToolCall("call-1", "search", "{\"q\":\"test\"}"),
                new ToolCall("call-2", "read_file", "{\"path\":\"README.md\"}"))));

        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("assistant");
        assertThat(captor.getValue().getToolCallId()).isEqualTo("call-1");
        assertThat(captor.getValue().getToolCallName()).isEqualTo("search");
        assertThat(captor.getValue().getToolCallArguments()).isEqualTo("{\"q\":\"test\"}");
        assertThat(captor.getValue().getToolCalls())
            .contains("\"id\":\"call-1\"")
            .contains("\"id\":\"call-2\"")
            .contains("\"name\":\"read_file\"");
    }

    @Test
    void persistTurnSavesGeneratedToolHistoryBeforeFinalAssistant() {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(messageRepository.countBySessionId(SESSION_ID)).thenReturn(4L);
        OpenAiSessionService.OpenAiSessionContext context =
            new OpenAiSessionService.OpenAiSessionContext(session, false, null);
        ToolCall toolCall = new ToolCall("call-1", "search", "{\"q\":\"test\"}");

        service.persistTurn(
            context,
            List.of(Message.user("question")),
            ChatResponse.text("fallback answer"),
            List.of(
                Message.assistantToolCalls(List.of(toolCall), 1),
                Message.toolResult("call-1", "tool payload", 1),
                Message.assistant("answer", 1)
            ));

        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(4)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(MessageEntity::getRole)
            .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(captor.getAllValues()).extracting(MessageEntity::getContent)
            .containsExactly("question", null, "tool payload", "answer");
        assertThat(captor.getAllValues().get(1).getToolCallId()).isEqualTo("call-1");
        assertThat(captor.getAllValues().get(1).getToolCallName()).isEqualTo("search");
        assertThat(captor.getAllValues().get(1).getToolCallArguments()).isEqualTo("{\"q\":\"test\"}");
        assertThat(captor.getAllValues().get(2).getToolCallId()).isEqualTo("call-1");
        assertThat(captor.getAllValues().get(2).getToolCallName()).isEqualTo("search");
        verify(sessionRepository).updatePreview(SESSION_ID, "question");
    }
}
