package com.azhukov.agent.service;

import com.azhukov.agent.api.AgentException;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.persistence.service.ToolResultNameResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OpenAiSessionService {

    public static final String SESSION_ID_HEADER = "X-Hermes-Session-Id";
    public static final String SESSION_KEY_HEADER = "X-Hermes-Session-Key";

    private static final int MAX_SESSION_HEADER_LENGTH = 256;
    private static final Pattern PROVIDER_PREFIX = Pattern.compile("^[a-zA-Z0-9_.-]{2,64}$");

    private final AgentSessionResolver sessionResolver;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final TransactionTemplate transactionTemplate;
    private final AgentProperties properties;

    public record OpenAiSessionContext(
        Session session,
        boolean continuationRequested,
        String sessionKey,
        String externalSessionId
    ) {
        public OpenAiSessionContext(Session session, boolean continuationRequested, String sessionKey) {
            this(session, continuationRequested, sessionKey, null);
        }

        public String responseSessionId() {
            if (externalSessionId != null && !externalSessionId.isBlank()) {
                return externalSessionId;
            }
            return session != null && session.id() != null ? session.id().toString() : "";
        }
    }

    public OpenAiSessionContext resolve(String sessionIdHeader, String sessionKeyHeader, String modelName) {
        return resolve(sessionIdHeader, sessionKeyHeader, modelName, null);
    }

    public OpenAiSessionContext resolveChatCompletions(String sessionIdHeader,
                                                       String sessionKeyHeader,
                                                       String modelName,
                                                       String statelessSeed) {
        return resolve(sessionIdHeader, sessionKeyHeader, modelName, statelessSeed);
    }

    private OpenAiSessionContext resolve(String sessionIdHeader,
                                         String sessionKeyHeader,
                                         String modelName,
                                         String statelessSeed) {
        String sessionKey = parseSessionKey(sessionKeyHeader);
        String requestedSessionId = parseSessionIdHeader(sessionIdHeader);
        if (requestedSessionId == null) {
            String userId = sessionKey != null ? sessionKey : AgentProperties.DEFAULT_USER_ID;
            UUID derivedSessionId = deterministicChatSessionUuid(userId, statelessSeed);
            Session session = derivedSessionId != null
                ? sessionResolver.loadOrCreateSession(
                    derivedSessionId,
                    userId,
                    "openai-compatible",
                    effectiveModelName(modelName),
                    "api_server")
                : sessionResolver.createSession(userId, "openai-compatible", effectiveModelName(modelName), "api_server");
            return new OpenAiSessionContext(
                session,
                false,
                sessionKey
            );
        }

        requireConfiguredApiKey("Session continuation requires API key authentication. "
            + "Configure API_SERVER_KEY to enable this feature.");
        return resolveExternalOrUuidSession(requestedSessionId, sessionKey, modelName, true);
    }

    static UUID deterministicChatSessionUuid(String userId, String statelessSeed) {
        if (statelessSeed == null || statelessSeed.isBlank()) {
            return null;
        }
        String scopedSeed = (userId == null || userId.isBlank() ? AgentProperties.DEFAULT_USER_ID : userId)
            + "\n"
            + statelessSeed;
        return UUID.nameUUIDFromBytes(("hermes-api-chat\n" + scopedSeed).getBytes(StandardCharsets.UTF_8));
    }

    public OpenAiSessionContext resolveStoredResponseSession(UUID sessionId, String sessionKeyHeader) {
        String sessionKey = parseSessionKey(sessionKeyHeader);
        UUID resolvedId = sessionResolver.resolveResumeSessionId(sessionId);
        if (!sessionRepository.existsById(resolvedId)) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return new OpenAiSessionContext(sessionResolver.loadSession(resolvedId), false, sessionKey);
    }

    public OpenAiSessionContext resolveRunSession(UUID sessionId, String sessionKeyHeader, String modelName) {
        String sessionKey = parseSessionKey(sessionKeyHeader);
        if (sessionId == null) {
            String userId = sessionKey != null ? sessionKey : AgentProperties.DEFAULT_USER_ID;
            return new OpenAiSessionContext(
                sessionResolver.createSession(userId, "openai-compatible", effectiveModelName(modelName), "api_server"),
                false,
                sessionKey
            );
        }
        UUID resolvedId = sessionResolver.resolveResumeSessionId(sessionId);
        if (!sessionRepository.existsById(resolvedId)) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return new OpenAiSessionContext(sessionResolver.loadSession(resolvedId), true, sessionKey);
    }

    public OpenAiSessionContext resolveRunSession(String sessionId, String sessionKeyHeader, String modelName) {
        String sessionKey = parseSessionKey(sessionKeyHeader);
        String requestedSessionId = parseHeaderValue(sessionId, "Invalid session_id", "Session ID too long");
        if (requestedSessionId == null) {
            String userId = sessionKey != null ? sessionKey : AgentProperties.DEFAULT_USER_ID;
            return new OpenAiSessionContext(
                sessionResolver.createSession(userId, "openai-compatible", effectiveModelName(modelName), "api_server"),
                false,
                sessionKey
            );
        }
        return resolveExternalOrUuidSession(requestedSessionId, sessionKey, modelName, true);
    }

    private OpenAiSessionContext resolveExternalOrUuidSession(String requestedSessionId,
                                                             String sessionKey,
                                                             String modelName,
                                                             boolean continuationRequested) {
        UUID uuidSessionId = tryParseUuid(requestedSessionId);
        if (uuidSessionId != null) {
            UUID resolvedId = sessionResolver.resolveResumeSessionId(uuidSessionId);
            if (!sessionRepository.existsById(resolvedId)) {
                throw new IllegalArgumentException("Session not found: " + requestedSessionId);
            }
            return new OpenAiSessionContext(
                sessionResolver.loadSession(resolvedId),
                continuationRequested,
                sessionKey
            );
        }

        String userId = sessionKey != null ? sessionKey : AgentProperties.DEFAULT_USER_ID;
        UUID mappedSessionId = deterministicExternalSessionUuid(userId, requestedSessionId);
        Session session = sessionResolver.loadOrCreateSession(
            mappedSessionId,
            userId,
            "openai-compatible",
            effectiveModelName(modelName),
            "api_server"
        );
        return new OpenAiSessionContext(session, continuationRequested, sessionKey, requestedSessionId);
    }

    public List<Message> historyFor(OpenAiSessionContext context) {
        if (context == null || context.session() == null || !context.continuationRequested()) {
            return List.of();
        }
        return sessionResolver.loadMessagesWithAncestors(context.session().id()).stream()
            .filter(message -> message != null
                && message.role() != Role.SYSTEM
                && message.role() != Role.DEVELOPER)
            .toList();
    }

    public void persistTurn(OpenAiSessionContext context, List<Message> incomingMessages, ChatResponse response) {
        persistTurn(context, incomingMessages, response, List.of());
    }

    public void persistTurn(OpenAiSessionContext context,
                            List<Message> incomingMessages,
                            ChatResponse response,
                            List<Message> generatedMessages) {
        if (context == null || context.session() == null || context.session().id() == null) {
            return;
        }
        transactionTemplate.execute(status -> {
            UUID sessionId = context.session().id();
            if (!sessionRepository.existsById(sessionId)) {
                return null;
            }
            Instant now = Instant.now();
            List<Message> persistedMessages = new ArrayList<>();
            List<Message> messagesToPersist = new ArrayList<>();
            if (incomingMessages != null) {
                messagesToPersist.addAll(incomingMessages);
            }
            if (generatedMessages != null && !generatedMessages.isEmpty()) {
                messagesToPersist.addAll(generatedMessages);
            } else {
                Message assistant = assistantMessage(response);
                if (assistant != null) {
                    messagesToPersist.add(assistant);
                }
            }
            Map<String, String> toolNamesByCallId = ToolResultNameResolver.collect(messagesToPersist);
            for (Message message : messagesToPersist) {
                if (persistMessage(sessionId, message, now, toolNamesByCallId)) {
                    persistedMessages.add(message);
                }
            }
            updateSessionStats(sessionId, persistedMessages, now);
            return null;
        });
    }

    public void persistHistory(UUID sessionId, List<Message> messages) {
        if (sessionId == null || messages == null || messages.isEmpty()) {
            return;
        }
        transactionTemplate.execute(status -> {
            if (!sessionRepository.existsById(sessionId)) {
                return null;
            }
            Instant now = Instant.now();
            List<Message> persistedMessages = new ArrayList<>();
            Map<String, String> toolNamesByCallId = ToolResultNameResolver.collect(messages);
            for (Message message : messages) {
                if (persistMessage(sessionId, message, now, toolNamesByCallId)) {
                    persistedMessages.add(message);
                }
            }
            updateSessionStats(sessionId, persistedMessages, now);
            return null;
        });
    }

    private Message assistantMessage(ChatResponse response) {
        if (response == null) {
            return null;
        }
        if (response.hasToolCalls()) {
            return Message.assistantWithToolCalls(response.content(), response.toolCalls(), 1);
        }
        if (response.content() == null || response.content().isBlank()) {
            return null;
        }
        return Message.assistant(response.content(), 1);
    }

    private boolean persistMessage(UUID sessionId, Message message, Instant now, Map<String, String> toolNamesByCallId) {
        if (message == null || message.role() == Role.SYSTEM || message.role() == Role.DEVELOPER) {
            return false;
        }
        MessageEntity entity = messageMapper.toEntity(message);
        ToolResultNameResolver.apply(entity, message, toolNamesByCallId);
        entity.setSessionId(sessionId);
        entity.setCreatedAt(now);
        entity.setActive(true);
        entity.setCompacted(false);
        messageRepository.save(entity);
        return true;
    }

    private void updateSessionStats(UUID sessionId, List<Message> persistedMessages, Instant now) {
        long messageCount = messageRepository.countBySessionId(sessionId);
        sessionRepository.updateLastActiveAndMessageCount(sessionId, now, (int) Math.min(messageCount, Integer.MAX_VALUE));
        sessionRepository.touchUpdatedAt(sessionId, now);
        for (int i = persistedMessages.size() - 1; i >= 0; i--) {
            Message message = persistedMessages.get(i);
            if (message.role() == Role.USER && message.content() != null && !message.content().isBlank()) {
                sessionRepository.updatePreview(sessionId, preview(message.content()));
                return;
            }
        }
    }

    private String preview(String content) {
        return content.length() > 200 ? content.substring(0, 197) + "..." : content;
    }

    private String parseSessionIdHeader(String raw) {
        return parseHeaderValue(raw, "Invalid session ID", "Session ID too long");
    }

    private String parseSessionKey(String raw) {
        String value = parseHeaderValue(raw, "Invalid session key", "Session key too long");
        if (value != null) {
            requireConfiguredApiKey("X-Hermes-Session-Key requires API key authentication. "
                + "Configure API_SERVER_KEY to enable this feature.");
        }
        return value;
    }

    private String parseHeaderValue(String raw, String invalidMessage, String tooLongMessage) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (containsControlCharacter(value)) {
            throw new IllegalArgumentException(invalidMessage);
        }
        if (value.length() > MAX_SESSION_HEADER_LENGTH) {
            throw new IllegalArgumentException(tooLongMessage);
        }
        return value;
    }

    private boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\r' || ch == '\n' || ch == '\0') {
                return true;
            }
        }
        return false;
    }

    static UUID deterministicExternalSessionUuid(String userId, String externalSessionId) {
        String scoped = (userId == null || userId.isBlank() ? AgentProperties.DEFAULT_USER_ID : userId)
            + "\n"
            + (externalSessionId == null ? "" : externalSessionId);
        return UUID.nameUUIDFromBytes(("hermes-api-session\n" + scoped).getBytes(StandardCharsets.UTF_8));
    }

    private UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String effectiveModelName(String requestedModel) {
        String model = splitProviderPrefixedModel(requestedModel);
        if (model != null && isAdvertisedApiAlias(model)) {
            return "";
        }
        if (model != null && !model.isBlank()) {
            return model;
        }
        return "";
    }

    private String splitProviderPrefixedModel(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return null;
        }
        String model = requestedModel.trim();
        if (model.contains("::")) {
            String[] parts = model.split("::", 2);
            String prefix = parts[0].trim();
            String splitModel = parts.length > 1 ? parts[1].trim() : "";
            if (PROVIDER_PREFIX.matcher(prefix).matches() && !splitModel.isBlank()) {
                model = splitModel;
            }
        }
        return model;
    }

    private boolean isAdvertisedApiAlias(String model) {
        if (model == null || model.isBlank() || properties.getApi() == null) {
            return false;
        }
        String advertised = properties.getApi().getModelName();
        if (advertised == null || advertised.isBlank() || !model.equals(advertised.trim())) {
            return false;
        }
        String configured = properties.getModel().getModelName();
        return configured == null || configured.isBlank() || !model.equals(configured.trim());
    }

    private void requireConfiguredApiKey(String message) {
        String apiKey = properties.getSecurity().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AgentException(HttpStatus.FORBIDDEN, message);
        }
    }
}
