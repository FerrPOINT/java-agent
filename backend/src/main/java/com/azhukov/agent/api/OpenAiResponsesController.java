package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.api.mapper.OpenAiMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.security.ApiErrorTextRedactor;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.OpenAiIdempotencyCache;
import com.azhukov.agent.service.ApiRunAdmissionService;
import com.azhukov.agent.service.OpenAiResponseStore;
import com.azhukov.agent.service.OpenAiResponseStore.StoredResponse;
import com.azhukov.agent.service.OpenAiSessionService;
import com.azhukov.agent.service.OpenAiSessionService.OpenAiSessionContext;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping({"/v1/responses", "/p/{profile}/v1/responses"})
@RequiredArgsConstructor
public class OpenAiResponsesController {

    private static final int RESPONSES_AUTO_TRUNCATION_HISTORY_LIMIT = 100;
    private static final List<String> COMPACTION_PREFIXES = List.of(
        "[CONTEXT COMPACTION", "[CONTEXT SUMMARY]:"
    );

    private final AgentRuntime agentRuntime;
    private final ToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final OpenAiMapper openAiMapper;
    private final AgentProperties properties;
    private final OpenAiSessionService openAiSessionService;
    private final OpenAiResponseStore responseStore;
    private final OpenAiIdempotencyCache idempotencyCache;
    private final Redactor redactor;
    private final ApiRunAdmissionService runAdmissionService;

    @PostMapping
    public ResponseEntity<?> createResponse(
            @RequestBody OpenAiResponsesRequest request,
            @RequestHeader(value = OpenAiSessionService.SESSION_ID_HEADER, required = false) String sessionIdHeader,
            @RequestHeader(value = OpenAiSessionService.SESSION_KEY_HEADER, required = false) String sessionKeyHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (request == null || request.input() == null) {
            return openAiError(HttpStatus.BAD_REQUEST, "Missing 'input' field", "invalid_request_error");
        }
        if (hasText(request.conversation()) && hasText(request.previousResponseId())) {
            return openAiError(HttpStatus.BAD_REQUEST,
                "Cannot use both 'conversation' and 'previous_response_id'",
                "invalid_request_error");
        }

        String previousResponseId = previousResponseId(request);
        StoredResponse previous = null;
        List<Message> priorHistory = null;
        String instructions = normalizeInstructions(request.instructions());
        if (hasExplicitConversationHistory(request)) {
            ParseResult parsedHistory = parseHistory(request.conversationHistory());
            if (parsedHistory.error() != null) {
                return openAiError(HttpStatus.BAD_REQUEST, parsedHistory.error(), "invalid_request_error",
                    parsedHistory.code(), parsedHistory.param());
            }
            priorHistory = parsedHistory.messages();
        } else if (hasText(previousResponseId)) {
            previous = responseStore.get(previousResponseId);
            if (previous == null) {
                return openAiError(HttpStatus.NOT_FOUND,
                    "Previous response not found: " + previousResponseId,
                    "invalid_request_error");
            }
            priorHistory = previous.conversationHistory();
            if (!hasText(instructions)) {
                instructions = previous.instructions();
            }
        }

        ParseResult parsedInput = parseInput(request.input());
        if (parsedInput.error() != null) {
            return openAiError(HttpStatus.BAD_REQUEST, parsedInput.error(), "invalid_request_error",
                parsedInput.code(), parsedInput.param());
        }
        List<Message> inputMessages = parsedInput.messages();
        if (inputMessages.isEmpty() || !hasVisiblePayload(inputMessages.get(inputMessages.size() - 1))) {
            return openAiError(HttpStatus.BAD_REQUEST, "No user message found in input", "invalid_request_error");
        }
        priorHistory = autoTruncateResponseHistory(priorHistory, request.truncation());

        String requestedModel = requestedModel(request.model());
        String routeConflict = OpenAiRouteSelection.routeProviderConflict(
            properties.getApi(),
            request.model(),
            request.provider(),
            false
        );
        if (routeConflict != null) {
            return openAiError(HttpStatus.BAD_REQUEST, routeConflict, "invalid_request_error");
        }
        if (!OpenAiRequestBooleans.coerce(request.stream(), false)) {
            StoredResponse storedPrevious = previous;
            List<Message> storedPriorHistory = priorHistory;
            String storedInstructions = instructions;
            return idempotencyCache.getOrCompute(
                idempotencyKey,
                () -> idempotencyCache.fingerprint(
                    "responses",
                    objectMapper,
                    request,
                    sessionIdHeader,
                    sessionKeyHeader),
                () -> syncResponse(
                    request,
                    sessionIdHeader,
                    sessionKeyHeader,
                    inputMessages,
                    storedPriorHistory,
                    storedInstructions,
                    requestedModel,
                    storedPrevious)
            );
        }
        OpenAiSessionContext sessionContext = resolveSession(
            sessionIdHeader,
            sessionKeyHeader,
            requestedModel,
            previous
        );
        List<Message> messages = buildMessages(sessionContext, instructions, priorHistory, inputMessages, request.truncation());
        List<ToolDefinition> tools = buildTools(request);
        ModelRequestOptions requestOptions = requestOptions(request, requestedModel);

        return streamResponse(
            request,
            sessionContext,
            inputMessages,
            messages,
            tools,
            requestOptions,
            priorHistory,
            instructions,
            requestedModel);
    }

    private ResponseEntity<?> syncResponse(OpenAiResponsesRequest request,
                                           String sessionIdHeader,
                                           String sessionKeyHeader,
                                           List<Message> inputMessages,
                                           List<Message> priorHistory,
                                           String instructions,
                                           String requestedModel,
                                           StoredResponse previous) {
        OpenAiSessionContext sessionContext = resolveSession(
            sessionIdHeader,
            sessionKeyHeader,
            requestedModel,
            previous
        );
        List<Message> messages = buildMessages(sessionContext, instructions, priorHistory, inputMessages, request.truncation());
        List<ToolDefinition> tools = buildTools(request);
        ModelRequestOptions requestOptions = requestOptions(request, requestedModel);
        var reservation = runAdmissionService.tryAcquire();
        if (reservation.isEmpty()) {
            return concurrencyLimitedResponse();
        }
        AgentCallResult result;
        try (ApiRunAdmissionService.Reservation ignored = reservation.get()) {
            result = runAgent(messages, tools, requestOptions);
        }
        ChatResponse response = result.response();
        openAiSessionService.persistTurn(sessionContext, inputMessages, response, result.generatedMessages());

        String responseId = responseId();
        Map<String, Object> responseBody = responseBody(
            responseId,
            requestedModel,
            response,
            result.generatedMessages());
        if (OpenAiRequestBooleans.coerce(request.store(), true)) {
            responseStore.put(responseId, new StoredResponse(
                responseBody,
                storedConversationHistory(priorHistory, inputMessages, result),
                instructions,
                sessionContext.session().id()
            ), request.conversation());
        }

        return ResponseEntity.ok()
            .headers(responseHeaders(sessionContext))
            .contentType(MediaType.APPLICATION_JSON)
            .body(responseBody);
    }

    private ResponseEntity<?> streamResponse(OpenAiResponsesRequest request,
                                             OpenAiSessionContext sessionContext,
                                             List<Message> inputMessages,
                                             List<Message> messages,
                                             List<ToolDefinition> tools,
                                             ModelRequestOptions requestOptions,
                                             List<Message> priorHistory,
                                             String instructions,
                                             String requestedModel) {
        var reservation = runAdmissionService.tryAcquire();
        if (reservation.isEmpty()) {
            return concurrencyLimitedResponse();
        }
        SseEmitter emitter = new SseEmitter(600_000L);
        String responseId = responseId();
        long createdAt = Instant.now().getEpochSecond();
        AtomicInteger sequenceNumber = new AtomicInteger();
        AtomicInteger outputIndex = new AtomicInteger();
        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicBoolean messageOpened = new AtomicBoolean(false);
        AtomicInteger messageOutputIndex = new AtomicInteger(-1);
        String messageItemId = "msg_" + randomHex(24);
        StringBuilder streamedContent = new StringBuilder();
        List<ToolCall> streamedToolCalls = new ArrayList<>();
        boolean shouldStore = OpenAiRequestBooleans.coerce(request.store(), true);

        Map<String, Object> createdResponse = responseBody(
            responseId,
            requestedModel,
            "in_progress",
            createdAt,
            List.of());
        if (shouldStore) {
            responseStore.put(responseId, new StoredResponse(
                createdResponse,
                historyWithoutAssistant(priorHistory, inputMessages),
                instructions,
                sessionContext.session().id()
            ), request.conversation());
        }

        CompletableFuture.runAsync(() -> {
            try {
                sendResponsesSse(emitter, sequenceNumber, "response.created", Map.of(
                    "type", "response.created",
                    "response", createdResponse
                ));
                AgentCallResult result = runAgent(messages, tools, requestOptions);
                ChatResponse response = result.response();
                if (hasGeneratedToolItems(result.generatedMessages())) {
                    emitGeneratedToolItemEvents(
                        result.generatedMessages(),
                        emitter,
                        sequenceNumber,
                        outputIndex,
                        streamedToolCalls);
                } else if (response.hasToolCalls()) {
                    synchronized (streamedToolCalls) {
                        streamedToolCalls.addAll(response.toolCalls());
                    }
                    for (ToolCall toolCall : response.toolCalls()) {
                        int index = outputIndex.getAndIncrement();
                        String itemId = "fc_" + randomHex(24);
                        Map<String, Object> addedItem = functionCallItem(toolCall, itemId, "in_progress");
                        sendResponsesSse(emitter, sequenceNumber, "response.output_item.added", Map.of(
                            "type", "response.output_item.added",
                            "output_index", index,
                            "item", addedItem
                        ));
                        sendResponsesSse(emitter, sequenceNumber, "response.output_item.done", Map.of(
                            "type", "response.output_item.done",
                            "output_index", index,
                            "item", functionCallItem(toolCall, itemId, "completed")
                        ));
                    }
                }
                if (response.content() != null && !response.content().isEmpty()) {
                    openMessageItemIfNeeded(
                        emitter,
                        sequenceNumber,
                        outputIndex,
                        messageOpened,
                        messageOutputIndex,
                        messageItemId);
                    synchronized (streamedContent) {
                        streamedContent.append(response.content());
                    }
                    sendResponsesSse(emitter, sequenceNumber, "response.output_text.delta", Map.of(
                        "type", "response.output_text.delta",
                        "item_id", messageItemId,
                        "output_index", messageOutputIndex.get(),
                        "content_index", 0,
                        "delta", response.content(),
                        "logprobs", List.of()
                    ));
                }
                completeResponsesStream(
                    request,
                    sessionContext,
                    inputMessages,
                    priorHistory,
                    instructions,
                    requestedModel,
                    responseId,
                    createdAt,
                    emitter,
                    sequenceNumber,
                    terminal,
                    messageOpened,
                    messageOutputIndex,
                    messageItemId,
                    streamedContent,
                    streamedToolCalls,
                    result.generatedMessages(),
                    result.conversationHistoryReplacement(),
                    shouldStore);
            } catch (Exception e) {
                failResponsesStream(
                    request,
                    sessionContext,
                    inputMessages,
                    priorHistory,
                    instructions,
                    requestedModel,
                    responseId,
                    createdAt,
                    emitter,
                    sequenceNumber,
                    terminal,
                    streamedContent,
                    streamedToolCalls,
                    shouldStore,
                    e);
            } finally {
                reservation.get().close();
            }
        });

        return ResponseEntity.ok()
            .headers(sseResponseHeaders(sessionContext))
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    @GetMapping("/{responseId}")
    public ResponseEntity<Map<String, Object>> getResponse(@PathVariable String responseId) {
        StoredResponse stored = responseStore.get(responseId);
        if (stored == null) {
            return openAiError(HttpStatus.NOT_FOUND, "Response not found: " + responseId, "invalid_request_error");
        }
        return ResponseEntity.ok(stored.response());
    }

    @DeleteMapping("/{responseId}")
    public ResponseEntity<Map<String, Object>> deleteResponse(@PathVariable String responseId) {
        if (!responseStore.delete(responseId)) {
            return openAiError(HttpStatus.NOT_FOUND, "Response not found: " + responseId, "invalid_request_error");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", responseId);
        body.put("object", "response");
        body.put("deleted", true);
        return ResponseEntity.ok(body);
    }

    private OpenAiSessionContext resolveSession(String sessionIdHeader,
                                                String sessionKeyHeader,
                                                String requestedModel,
                                                StoredResponse previous) {
        if (hasText(sessionIdHeader)) {
            return openAiSessionService.resolve(sessionIdHeader, sessionKeyHeader, requestedModel);
        }
        if (previous != null && previous.sessionId() != null) {
            return openAiSessionService.resolveStoredResponseSession(previous.sessionId(), sessionKeyHeader);
        }
        return openAiSessionService.resolve(null, sessionKeyHeader, requestedModel);
    }

    private AgentCallResult runAgent(List<Message> messages,
                                     List<ToolDefinition> tools,
                                     ModelRequestOptions requestOptions) {
        TurnResult turnResult = agentRuntime.runMessages(messages, tools, requestOptions);
        if (turnResult != null) {
            ChatResponse response = chatResponseFrom(turnResult);
            return new AgentCallResult(
                response,
                generatedMessages(messages, turnResult, response),
                compressedConversationHistoryReplacement(messages, turnResult.messages()));
        }
        ChatResponse response = agentRuntime.run(messages, tools, requestOptions);
        return new AgentCallResult(response != null ? response : ChatResponse.text(""), List.of(), null);
    }

    private ChatResponse chatResponseFrom(TurnResult result) {
        if (result == null) {
            return ChatResponse.text("");
        }
        if (result.error() != null && !result.error().isBlank()) {
            return ChatResponse.text(apiErrorText(result.error()));
        }
        for (int i = result.messages().size() - 1; i >= 0; i--) {
            Message message = result.messages().get(i);
            if (message.role() == Role.ASSISTANT
                && (message.toolCalls() == null || message.toolCalls().isEmpty())) {
                return ChatResponse.text(message.content());
            }
        }
        return ChatResponse.text(result.finalText());
    }

    private List<Message> generatedMessages(List<Message> initialMessages, TurnResult result, ChatResponse response) {
        if (result == null || result.messages().isEmpty()) {
            return List.of();
        }
        if (isCompressedTranscriptReplacement(initialMessages, result.messages())) {
            int start = generatedSuffixStart(initialMessages, result.messages());
            if (start >= 0 && start < result.messages().size()) {
                return List.copyOf(result.messages().subList(start, result.messages().size()));
            }
            return assistantOnly(response);
        }
        int initialSize = initialMessages != null ? initialMessages.size() : 0;
        if (result.messages().size() > initialSize) {
            return List.copyOf(result.messages().subList(initialSize, result.messages().size()));
        }
        if (result.error() != null && response.content() != null && !response.content().isBlank()) {
            return List.of(Message.assistant(response.content(), 1));
        }
        return List.copyOf(result.messages());
    }

    private List<Message> assistantOnly(ChatResponse response) {
        return List.of(assistantMessage(response));
    }

    private List<Message> compressedConversationHistoryReplacement(List<Message> initialMessages,
                                                                   List<Message> resultMessages) {
        if (!isCompressedTranscriptReplacement(initialMessages, resultMessages)) {
            return null;
        }
        int start = 0;
        if (!resultMessages.isEmpty()) {
            Message first = resultMessages.get(0);
            if ((first.role() == Role.SYSTEM || first.role() == Role.DEVELOPER) && !isCompactionSummary(first)) {
                start = 1;
            }
        }
        return List.copyOf(resultMessages.subList(start, resultMessages.size()));
    }

    private boolean isCompressedTranscriptReplacement(List<Message> initialMessages, List<Message> resultMessages) {
        return resultMessages != null
            && !resultMessages.isEmpty()
            && resultMessages.stream().anyMatch(this::isCompactionSummary)
            && !startsWithMessages(resultMessages, initialMessages);
    }

    private boolean startsWithMessages(List<Message> messages, List<Message> prefix) {
        if (messages == null || prefix == null || messages.size() < prefix.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!sameMessageShape(messages.get(i), prefix.get(i))) {
                return false;
            }
        }
        return true;
    }

    private int generatedSuffixStart(List<Message> initialMessages, List<Message> resultMessages) {
        if (initialMessages == null || initialMessages.isEmpty()) {
            return -1;
        }
        Message terminalInput = initialMessages.get(initialMessages.size() - 1);
        for (int i = resultMessages.size() - 1; i >= 0; i--) {
            if (sameMessageShape(resultMessages.get(i), terminalInput)) {
                return i + 1;
            }
        }
        return -1;
    }

    private boolean sameMessageShape(Message left, Message right) {
        return left != null
            && right != null
            && left.role() == right.role()
            && Objects.equals(left.content(), right.content())
            && Objects.equals(left.toolCallId(), right.toolCallId())
            && Objects.equals(left.toolCalls(), right.toolCalls())
            && Objects.equals(left.imageCount(), right.imageCount());
    }

    private record AgentCallResult(ChatResponse response,
                                   List<Message> generatedMessages,
                                   List<Message> conversationHistoryReplacement) {}

    private record PendingResponseToolCall(String itemId,
                                           int outputIndex,
                                           ToolCall toolCall,
                                           Set<String> variants) {}

    private List<Message> buildMessages(OpenAiSessionContext sessionContext,
                                        String instructions,
                                        List<Message> priorHistory,
                                        List<Message> inputMessages,
                                        String truncation) {
        Session session = sessionContext.session();
        List<Message> messages = new ArrayList<>();
        messages.add(promptBuilder.buildSystemMessage(session, instructions));
        if (priorHistory != null) {
            messages.addAll(priorHistory);
        } else if (sessionContext.continuationRequested()) {
            messages.addAll(autoTruncateResponseHistory(openAiSessionService.historyFor(sessionContext), truncation));
        }
        messages.addAll(inputMessages);
        return messages;
    }

    private List<ToolDefinition> buildTools(OpenAiResponsesRequest request) {
        if (request.tools() != null) {
            return request.tools().stream()
                .map(this::toToolDefinition)
                .filter(Objects::nonNull)
                .toList();
        }
        Set<String> apiToolsets = new LinkedHashSet<>();
        if (properties.getApi().getChatCompletionToolsets() != null) {
            properties.getApi().getChatCompletionToolsets().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(apiToolsets::add);
        }
        if (apiToolsets.isEmpty()) {
            return List.of();
        }
        return toolRegistry.getDefinitions(apiToolsets);
    }

    private ToolDefinition toToolDefinition(OpenAiResponsesTool tool) {
        if (tool == null) {
            return null;
        }
        if (tool.function() != null) {
            return openAiMapper.toToolDefinition(new OpenAiChatRequest.OpenAiTool(tool.type(), tool.function()));
        }
        if (tool.type() != null && !"function".equals(tool.type().trim())) {
            return null;
        }
        String name = tool.name() != null ? tool.name().trim() : "";
        if (name.isBlank()) {
            return null;
        }
        Map<String, Object> parameters = tool.parameters() != null
            ? tool.parameters()
            : Map.of("type", "object", "properties", Map.of(), "required", List.of());
        return new ToolDefinition(name, tool.description() != null ? tool.description() : "", parameters);
    }

    private String previousResponseId(OpenAiResponsesRequest request) {
        return responseStore.previousResponseId(request.previousResponseId(), request.conversation());
    }

    private boolean hasExplicitConversationHistory(OpenAiResponsesRequest request) {
        return isTruthy(request.conversationHistory());
    }

    private ParseResult parseInput(Object rawInput) {
        if (rawInput instanceof String text) {
            return ParseResult.success(List.of(Message.user(OpenAiContentNormalizer.normalizeConversationText(text))));
        }
        if (!(rawInput instanceof List<?> list)) {
            return ParseResult.error("'input' must be a string or array");
        }
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            ParseResult parsed = parseInputItem(item, "input[" + i + "]");
            if (parsed.error() != null) {
                return parsed;
            }
            messages.addAll(parsed.messages());
        }
        return ParseResult.success(messages);
    }

    private ParseResult parseInputItem(Object item, String path) {
        try {
            if (item instanceof String text) {
                return ParseResult.success(List.of(Message.user(OpenAiContentNormalizer.normalizeConversationText(text))));
            }
            if (!(item instanceof Map<?, ?> map)) {
                return ParseResult.error(path + " must be a string or object");
            }
            String type = stringValue(map.get("type")).toLowerCase(Locale.ROOT);
            if ("function_call_output".equals(type)) {
                String rawCallId = callIdFrom(map);
                if (!hasText(rawCallId)) {
                    return ParseResult.error(path + " function_call_output is missing call_id");
                }
                String callId = ToolCall.responsesCallId(rawCallId, "", "", 0);
                String output = OpenAiContentNormalizer.normalizeConversationText(map.get("output"));
                return ParseResult.success(List.of(Message.toolResult(callId, output, 0)));
            }
            if ("function_call".equals(type)) {
                String rawCallId = callIdFrom(map);
                if (!hasText(rawCallId)) {
                    return ParseResult.error(path + " function_call is missing call_id");
                }
                String rawName = stringValue(map.get("name"));
                if (!hasText(rawName)) {
                    return ParseResult.error(path + " function_call is missing name");
                }
                String name = ToolCall.sanitizeReplayedFunctionName(rawName);
                String arguments = normalizeToolCallArguments(map.get("arguments"));
                String callId = ToolCall.responsesCallId(rawCallId, name, arguments, 0);
                return ParseResult.success(List.of(Message.assistantToolCalls(
                    List.of(new ToolCall(callId, name, arguments)), 0)));
            }
            if (!type.isBlank() && !"message".equals(type) && !map.containsKey("role")) {
                return ParseResult.error("Unsupported input item type '" + type + "'");
            }
            String role = hasText(stringValue(map.get("role"))) ? stringValue(map.get("role")) : "user";
            Object content = map.containsKey("content") ? map.get("content") : "";
            String toolCallId = map.containsKey("tool_call_id")
                ? stringValue(map.get("tool_call_id"))
                : stringValue(map.get("toolCallId"));
            return ParseResult.success(List.of(toMessage(role, content, toolCallId, rawToolCalls(map))));
        } catch (IllegalArgumentException e) {
            return ParseResult.error(e.getMessage(), OpenAiContentNormalizer.errorCode(e), path + ".content");
        }
    }

    private ParseResult parseHistory(Object rawHistory) {
        if (!(rawHistory instanceof List<?> history)) {
            return ParseResult.error("'conversation_history' must be an array of message objects");
        }
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            Object item = history.get(i);
            if (!(item instanceof Map<?, ?> map)
                || !map.containsKey("role")
                || !map.containsKey("content")) {
                return ParseResult.error(
                    "conversation_history[" + i + "] must have 'role' and 'content' fields");
            }
            try {
                String role = stringValue(map.get("role"));
                String toolCallId = map.containsKey("tool_call_id")
                    ? stringValue(map.get("tool_call_id"))
                    : stringValue(map.get("toolCallId"));
                messages.add(toMessage(role, map.get("content"), toolCallId, rawToolCalls(map)));
            } catch (IllegalArgumentException e) {
                return ParseResult.error(e.getMessage(), OpenAiContentNormalizer.errorCode(e),
                    "conversation_history[" + i + "].content");
            }
        }
        return ParseResult.success(messages);
    }

    private Message toMessage(String rawRole, Object content, String toolCallId, Object rawToolCalls) {
        String role = rawRole != null ? rawRole.trim().toLowerCase(Locale.ROOT) : "user";
        OpenAiContentNormalizer.NormalizedConversationContent normalized =
            switch (role) {
                case "system", "developer" -> new OpenAiContentNormalizer.NormalizedConversationContent(
                    OpenAiContentNormalizer.normalizeSystemText(content),
                    0);
                default -> OpenAiContentNormalizer.normalizeConversationContent(content);
            };
        String text = normalized.text();
        int imageCount = normalized.imageCount();
        Message message = switch (role) {
            case "system" -> Message.system(text);
            case "developer" -> Message.developer(text);
            case "assistant" -> {
                List<ToolCall> toolCalls = parseToolCalls(rawToolCalls);
                yield toolCalls.isEmpty()
                    ? Message.assistant(text, 0)
                    : Message.assistantWithToolCalls(text, toolCalls, 0);
            }
            case "tool" -> Message.toolResult(
                hasText(toolCallId) ? ToolCall.responsesCallId(toolCallId, "", "", 0) : "",
                text,
                0);
            default -> imageCount > 0 ? Message.userWithImages(text, imageCount) : Message.user(text);
        };
        return imageCount > 0 && message.role() != Role.USER
            ? Message.withImageCount(message, imageCount)
            : message;
    }

    private Object rawToolCalls(Map<?, ?> map) {
        return map.containsKey("tool_calls") ? map.get("tool_calls") : map.get("toolCalls");
    }

    private List<ToolCall> parseToolCalls(Object rawToolCalls) {
        if (!(rawToolCalls instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        List<ToolCall> toolCalls = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Object raw = rawList.get(i);
            if (!(raw instanceof Map<?, ?> callMap)) {
                continue;
            }
            Object rawFunction = callMap.get("function");
            Map<?, ?> functionMap = rawFunction instanceof Map<?, ?> map ? map : Map.of();
            String name = stringValue(functionMap.get("name"));
            if (!hasText(name)) {
                name = stringValue(callMap.get("name"));
            }
            if (!hasText(name)) {
                continue;
            }
            String arguments = normalizeToolCallArguments(
                functionMap.containsKey("arguments") ? functionMap.get("arguments") : callMap.get("arguments"));
            String id = ToolCall.responsesCallId(callIdFrom(callMap), name, arguments, i);
            name = ToolCall.sanitizeReplayedFunctionName(name);
            toolCalls.add(new ToolCall(id, name, arguments));
        }
        return toolCalls.isEmpty() ? List.of() : List.copyOf(toolCalls);
    }

    private String normalizeToolCallArguments(Object arguments) {
        if (arguments instanceof Map<?, ?> || arguments instanceof List<?>) {
            try {
                return objectMapper.writeValueAsString(arguments);
            } catch (IOException ignored) {
                // Fall back to String.valueOf below.
            }
        }
        String value = stringValue(arguments);
        return hasText(value) ? value : "{}";
    }

    private String callIdFrom(Map<?, ?> map) {
        String value = stringValue(map.get("call_id"));
        if (hasText(value)) {
            return value;
        }
        value = stringValue(map.get("callId"));
        if (hasText(value)) {
            return value;
        }
        return stringValue(map.get("id"));
    }

    private boolean hasVisiblePayload(Message message) {
        return message != null && switch (message.role()) {
            case USER, TOOL -> message.content() != null && !message.content().isBlank()
                || message.imageCount() != null && message.imageCount() > 0;
            case ASSISTANT -> message.content() != null && !message.content().isBlank()
                || message.toolCalls() != null && !message.toolCalls().isEmpty()
                || message.imageCount() != null && message.imageCount() > 0;
            default -> OpenAiContentNormalizer.hasVisibleText(message.content());
        };
    }

    private List<Message> fullHistory(List<Message> priorHistory,
                                      List<Message> inputMessages,
                                      ChatResponse response) {
        return fullHistory(priorHistory, inputMessages, List.of(), response);
    }

    private List<Message> storedConversationHistory(List<Message> priorHistory,
                                                    List<Message> inputMessages,
                                                    AgentCallResult result) {
        return storedConversationHistory(
            priorHistory,
            inputMessages,
            result.generatedMessages(),
            result.response(),
            result.conversationHistoryReplacement());
    }

    private List<Message> storedConversationHistory(List<Message> priorHistory,
                                                    List<Message> inputMessages,
                                                    List<Message> generatedMessages,
                                                    ChatResponse response,
                                                    List<Message> conversationHistoryReplacement) {
        if (conversationHistoryReplacement != null) {
            return conversationHistoryReplacement;
        }
        return fullHistory(priorHistory, inputMessages, generatedMessages, response);
    }

    private List<Message> fullHistory(List<Message> priorHistory,
                                      List<Message> inputMessages,
                                      List<Message> generatedMessages,
                                      ChatResponse response) {
        List<Message> history = new ArrayList<>();
        if (priorHistory != null) {
            history.addAll(priorHistory);
        }
        history.addAll(inputMessages);
        if (generatedMessages != null && !generatedMessages.isEmpty()) {
            history.addAll(generatedMessages);
        } else {
            history.add(assistantMessage(response));
        }
        return List.copyOf(history);
    }

    private List<Message> historyWithoutAssistant(List<Message> priorHistory,
                                                  List<Message> inputMessages) {
        List<Message> history = new ArrayList<>();
        if (priorHistory != null) {
            history.addAll(priorHistory);
        }
        history.addAll(inputMessages);
        return List.copyOf(history);
    }

    private List<Message> autoTruncateResponseHistory(List<Message> history, String truncation) {
        if (!"auto".equals(truncation)
            || history == null
            || history.size() <= RESPONSES_AUTO_TRUNCATION_HISTORY_LIMIT) {
            return history;
        }
        int limit = RESPONSES_AUTO_TRUNCATION_HISTORY_LIMIT;
        boolean[] kept = new boolean[history.size()];
        int keptCount = 0;
        for (int i = 0; i < history.size() && keptCount < limit; i++) {
            if (isCompactionSummary(history.get(i))) {
                kept[i] = true;
                keptCount++;
            }
        }
        if (keptCount == 0) {
            return List.copyOf(history.subList(history.size() - limit, history.size()));
        }
        for (int i = history.size() - 1; i >= 0 && keptCount < limit; i--) {
            if (!kept[i]) {
                kept[i] = true;
                keptCount++;
            }
        }
        List<Message> truncated = new ArrayList<>(limit);
        for (int i = 0; i < history.size(); i++) {
            if (kept[i]) {
                truncated.add(history.get(i));
            }
        }
        return List.copyOf(truncated);
    }

    private boolean isCompactionSummary(Message message) {
        if (message == null || message.content() == null || message.content().isEmpty()) {
            return false;
        }
        String stripped = message.content().stripLeading();
        for (String prefix : COMPACTION_PREFIXES) {
            if (stripped.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Message assistantMessage(ChatResponse response) {
        if (response != null && response.hasToolCalls()) {
            return Message.assistantWithToolCalls(response.content(), response.toolCalls(), 1);
        }
        return Message.assistant(response != null ? response.content() : "", 1);
    }

    private Map<String, Object> responseBody(String responseId, String model, ChatResponse response) {
        return responseBody(responseId, model, "completed", Instant.now().getEpochSecond(), response);
    }

    private Map<String, Object> responseBody(String responseId,
                                             String model,
                                             ChatResponse response,
                                             List<Message> generatedMessages) {
        return responseBody(responseId, model, "completed", Instant.now().getEpochSecond(), response, generatedMessages);
    }

    private Map<String, Object> responseBody(String responseId,
                                             String model,
                                             String status,
                                             long createdAt,
                                             ChatResponse response) {
        return responseBody(responseId, model, status, createdAt, outputItems(response));
    }

    private Map<String, Object> responseBody(String responseId,
                                             String model,
                                             String status,
                                             long createdAt,
                                             ChatResponse response,
                                             List<Message> generatedMessages) {
        return responseBody(responseId, model, status, createdAt, outputItems(response, generatedMessages));
    }

    private Map<String, Object> responseBody(String responseId,
                                             String model,
                                             String status,
                                             long createdAt,
                                             List<Map<String, Object>> output) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", responseId);
        body.put("object", "response");
        body.put("status", status);
        body.put("created_at", createdAt);
        body.put("model", model);
        body.put("output", output);
        body.put("usage", usage());
        return body;
    }

    private void completeResponsesStream(OpenAiResponsesRequest request,
                                         OpenAiSessionContext sessionContext,
                                         List<Message> inputMessages,
                                         List<Message> priorHistory,
                                         String instructions,
                                         String requestedModel,
                                         String responseId,
                                         long createdAt,
                                         SseEmitter emitter,
                                         AtomicInteger sequenceNumber,
                                         AtomicBoolean terminal,
                                         AtomicBoolean messageOpened,
                                         AtomicInteger messageOutputIndex,
                                         String messageItemId,
                                         StringBuilder streamedContent,
                                         List<ToolCall> streamedToolCalls,
                                         List<Message> generatedMessages,
                                         List<Message> conversationHistoryReplacement,
                                         boolean shouldStore) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }

        ChatResponse response = streamingChatResponse(streamedContent, streamedToolCalls);
        openAiSessionService.persistTurn(sessionContext, inputMessages, response, generatedMessages);

        if (messageOpened.get()) {
            int index = messageOutputIndex.get();
            sendResponsesSse(emitter, sequenceNumber, "response.output_text.done", Map.of(
                "type", "response.output_text.done",
                "item_id", messageItemId,
                "output_index", index,
                "content_index", 0,
                "text", response.content(),
                "logprobs", List.of()
            ));
            sendResponsesSse(emitter, sequenceNumber, "response.output_item.done", Map.of(
                "type", "response.output_item.done",
                "output_index", index,
                "item", messageItem(messageItemId, "completed", response.content())
            ));
        }

        Map<String, Object> completedResponse = responseBody(
            responseId,
            requestedModel,
            "completed",
            createdAt,
            response,
            generatedMessages);
        if (shouldStore) {
                responseStore.put(responseId, new StoredResponse(
                    completedResponse,
                    storedConversationHistory(
                        priorHistory,
                        inputMessages,
                        generatedMessages,
                        response,
                        conversationHistoryReplacement),
                    instructions,
                    sessionContext.session().id()
                ), request.conversation());
        }
        sendResponsesSse(emitter, sequenceNumber, "response.completed", Map.of(
            "type", "response.completed",
            "response", completedResponse
        ));
        emitter.complete();
    }

    private void failResponsesStream(OpenAiResponsesRequest request,
                                     OpenAiSessionContext sessionContext,
                                     List<Message> inputMessages,
                                     List<Message> priorHistory,
                                     String instructions,
                                     String requestedModel,
                                     String responseId,
                                     long createdAt,
                                     SseEmitter emitter,
                                     AtomicInteger sequenceNumber,
                                     AtomicBoolean terminal,
                                     StringBuilder streamedContent,
                                     List<ToolCall> streamedToolCalls,
                                     boolean shouldStore,
                                     Throwable error) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }

        ChatResponse partialResponse = streamingChatResponse(streamedContent, streamedToolCalls);
        Map<String, Object> failedResponse = responseBody(
            responseId,
            requestedModel,
            "failed",
            createdAt,
            partialResponse);
        failedResponse.put("error", Map.of(
            "message", apiErrorText(errorMessage(error)),
            "type", "server_error"
        ));
        if (shouldStore) {
            responseStore.put(responseId, new StoredResponse(
                failedResponse,
                fullHistory(priorHistory, inputMessages, partialResponse),
                instructions,
                sessionContext.session().id()
            ), request.conversation());
        }
        sendResponsesSse(emitter, sequenceNumber, "response.failed", Map.of(
            "type", "response.failed",
            "response", failedResponse
        ));
        emitter.complete();
    }

    private ChatResponse streamingChatResponse(StringBuilder streamedContent, List<ToolCall> streamedToolCalls) {
        String content;
        List<ToolCall> toolCalls;
        synchronized (streamedContent) {
            content = streamedContent.toString();
        }
        synchronized (streamedToolCalls) {
            toolCalls = List.copyOf(streamedToolCalls);
        }
        if (toolCalls.isEmpty()) {
            return ChatResponse.text(content);
        }
        return ChatResponse.textAndToolCalls(content, toolCalls);
    }

    private boolean hasGeneratedToolItems(List<Message> generatedMessages) {
        if (generatedMessages == null) {
            return false;
        }
        for (Message message : generatedMessages) {
            if (message == null) {
                continue;
            }
            if (message.role() == Role.TOOL) {
                return true;
            }
            if (message.role() == Role.ASSISTANT && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void emitGeneratedToolItemEvents(List<Message> generatedMessages,
                                             SseEmitter emitter,
                                             AtomicInteger sequenceNumber,
                                             AtomicInteger outputIndex,
                                             List<ToolCall> streamedToolCalls) {
        Map<String, PendingResponseToolCall> pending = new LinkedHashMap<>();
        for (Message message : generatedMessages) {
            if (message == null) {
                continue;
            }
            if (message.role() == Role.ASSISTANT && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                synchronized (streamedToolCalls) {
                    streamedToolCalls.addAll(message.toolCalls());
                }
                for (ToolCall toolCall : message.toolCalls()) {
                    int index = outputIndex.getAndIncrement();
                    String itemId = "fc_" + randomHex(24);
                    putPendingToolCall(pending, new PendingResponseToolCall(
                        itemId,
                        index,
                        toolCall,
                        ToolCall.idVariants(toolCall)));
                    sendResponsesSse(emitter, sequenceNumber, "response.output_item.added", Map.of(
                        "type", "response.output_item.added",
                        "output_index", index,
                        "item", functionCallItem(toolCall, itemId, "in_progress")
                    ));
                }
            } else if (message.role() == Role.TOOL) {
                PendingResponseToolCall toolCall = removePendingToolCall(pending, message.toolCallId());
                if (toolCall == null) {
                    continue;
                }
                sendResponsesSse(emitter, sequenceNumber, "response.output_item.done", Map.of(
                    "type", "response.output_item.done",
                    "output_index", toolCall.outputIndex(),
                    "item", functionCallItem(toolCall.toolCall(), toolCall.itemId(), "completed")
                ));
                int resultIndex = outputIndex.getAndIncrement();
                Map<String, Object> outputItem = functionCallOutputStreamItem(message, toolCall.toolCall().id());
                sendResponsesSse(emitter, sequenceNumber, "response.output_item.added", Map.of(
                    "type", "response.output_item.added",
                    "output_index", resultIndex,
                    "item", outputItem
                ));
                sendResponsesSse(emitter, sequenceNumber, "response.output_item.done", Map.of(
                    "type", "response.output_item.done",
                    "output_index", resultIndex,
                    "item", outputItem
                ));
            }
        }
        for (PendingResponseToolCall toolCall : new LinkedHashSet<>(pending.values())) {
            sendResponsesSse(emitter, sequenceNumber, "response.output_item.done", Map.of(
                "type", "response.output_item.done",
                "output_index", toolCall.outputIndex(),
                "item", functionCallItem(toolCall.toolCall(), toolCall.itemId(), "completed")
            ));
        }
    }

    private void putPendingToolCall(Map<String, PendingResponseToolCall> pending, PendingResponseToolCall toolCall) {
        if (toolCall.variants().isEmpty()) {
            return;
        }
        toolCall.variants().forEach(variant -> pending.putIfAbsent(variant, toolCall));
    }

    private PendingResponseToolCall removePendingToolCall(Map<String, PendingResponseToolCall> pending, String toolCallId) {
        for (String variant : ToolCall.idVariants(toolCallId)) {
            PendingResponseToolCall match = pending.get(variant);
            if (match != null) {
                match.variants().forEach(pending::remove);
                ToolCall.idVariants(toolCallId).forEach(pending::remove);
                return match;
            }
        }
        return null;
    }

    private void openMessageItemIfNeeded(SseEmitter emitter,
                                         AtomicInteger sequenceNumber,
                                         AtomicInteger outputIndex,
                                         AtomicBoolean messageOpened,
                                         AtomicInteger messageOutputIndex,
                                         String messageItemId) {
        if (!messageOpened.compareAndSet(false, true)) {
            return;
        }
        int index = outputIndex.getAndIncrement();
        messageOutputIndex.set(index);
        sendResponsesSse(emitter, sequenceNumber, "response.output_item.added", Map.of(
            "type", "response.output_item.added",
            "output_index", index,
            "item", messageItem(messageItemId, "in_progress", null)
        ));
    }

    private Map<String, Object> messageItem(String itemId, String status, String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", itemId);
        item.put("type", "message");
        item.put("status", status);
        item.put("role", "assistant");
        if (text == null) {
            item.put("content", List.of());
        } else {
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "output_text");
            textPart.put("text", text);
            item.put("content", List.of(textPart));
        }
        return item;
    }

    private Map<String, Object> functionCallItem(ToolCall toolCall, String itemId, String status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", itemId);
        item.put("type", "function_call");
        item.put("status", status);
        item.put("name", toolCall.name());
        item.put("arguments", toolCall.arguments());
        item.put("call_id", toolCall.id());
        return item;
    }

    private Map<String, Object> functionCallOutputStreamItem(Message message, String callId) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "input_text");
        textPart.put("text", message.content() != null ? message.content() : "");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "fco_" + randomHex(24));
        item.put("type", "function_call_output");
        item.put("status", "completed");
        item.put("call_id", callId != null ? callId : "");
        item.put("output", List.of(textPart));
        return item;
    }

    private void sendResponsesSse(SseEmitter emitter,
                                  AtomicInteger sequenceNumber,
                                  String eventType,
                                  Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.putIfAbsent("sequence_number", sequenceNumber.getAndIncrement());
        try {
            emitter.send(SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(eventType)
                .data(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private String errorMessage(Throwable error) {
        if (error == null) {
            return "Streaming response failed";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private List<Map<String, Object>> outputItems(ChatResponse response) {
        return outputItems(response, List.of());
    }

    private List<Map<String, Object>> outputItems(ChatResponse response, List<Message> generatedMessages) {
        List<Map<String, Object>> items = new ArrayList<>();
        boolean projectedGeneratedToolItems = false;
        Map<String, PendingResponseToolCall> pending = new LinkedHashMap<>();
        if (generatedMessages != null) {
            for (Message message : generatedMessages) {
                if (message == null) {
                    continue;
                }
                if (message.role() == Role.ASSISTANT && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    for (ToolCall toolCall : message.toolCalls()) {
                        items.add(functionCallItem(toolCall, "fc_" + randomHex(24), "completed"));
                        putPendingToolCall(pending, new PendingResponseToolCall(
                            "",
                            -1,
                            toolCall,
                            ToolCall.idVariants(toolCall)));
                        projectedGeneratedToolItems = true;
                    }
                } else if (message.role() == Role.TOOL) {
                    PendingResponseToolCall toolCall = removePendingToolCall(pending, message.toolCallId());
                    items.add(functionCallOutputItem(
                        message,
                        toolCall != null ? toolCall.toolCall().id() : message.toolCallId()));
                    projectedGeneratedToolItems = true;
                }
            }
        }
        if (!projectedGeneratedToolItems && response != null && response.toolCalls() != null) {
            for (ToolCall toolCall : response.toolCalls()) {
                items.add(functionCallItem(toolCall, "fc_" + randomHex(24), "completed"));
            }
        }
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "output_text");
        textPart.put("text", response != null ? response.content() : "");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("content", List.of(textPart));
        items.add(message);
        return items;
    }

    private Map<String, Object> functionCallOutputItem(Message message) {
        return functionCallOutputItem(message, message.toolCallId());
    }

    private Map<String, Object> functionCallOutputItem(Message message, String callId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "fco_" + randomHex(24));
        item.put("type", "function_call_output");
        item.put("status", "completed");
        item.put("call_id", callId != null ? callId : "");
        item.put("output", message.content() != null ? message.content() : "");
        return item;
    }

    private Map<String, Object> usage() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        usage.put("total_tokens", 0);
        return usage;
    }

    private HttpHeaders responseHeaders(OpenAiSessionContext sessionContext) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(OpenAiSessionService.SESSION_ID_HEADER, sessionContext.responseSessionId());
        if (hasText(sessionContext.sessionKey())) {
            headers.add(OpenAiSessionService.SESSION_KEY_HEADER, sessionContext.sessionKey());
        }
        return headers;
    }

    private HttpHeaders sseResponseHeaders(OpenAiSessionContext sessionContext) {
        HttpHeaders headers = responseHeaders(sessionContext);
        headers.setCacheControl("no-cache");
        headers.add("X-Accel-Buffering", "no");
        return headers;
    }

    private ResponseEntity<Map<String, Object>> openAiError(HttpStatus status, String message, String type) {
        return openAiError(status, message, type, null, null);
    }

    private ResponseEntity<Map<String, Object>> concurrencyLimitedResponse() {
        int limit = runAdmissionService.maxConcurrentRuns();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", apiErrorText("Too many concurrent runs (max " + limit + ")"));
        error.put("type", "rate_limit_error");
        error.put("param", null);
        error.put("code", "rate_limit_exceeded");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("error", error));
    }

    private ResponseEntity<Map<String, Object>> openAiError(
            HttpStatus status,
            String message,
            String type,
            String code,
            String param) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", apiErrorText(message));
        error.put("type", type);
        error.put("param", param);
        error.put("code", code);
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("error", error));
    }

    private String requestedModel(Object requestModel) {
        if (requestModel instanceof String text && hasText(text)) {
            return text.trim();
        }
        String advertisedModel = OpenAiModelRouting.advertisedModel(properties);
        return hasText(advertisedModel) ? advertisedModel : "unknown";
    }

    private ModelRequestOptions requestOptions(OpenAiResponsesRequest request, String requestedModel) {
        return OpenAiRequestModelOptions.from(
            properties,
            requestedModel,
            request.provider(),
            request.modelOptions(),
            request.maxOutputTokens(),
            false,
            false);
    }

    private String normalizeInstructions(Object instructions) {
        if (instructions == null) {
            return null;
        }
        String normalized = OpenAiContentNormalizer.normalizeSystemText(instructions);
        return normalized.isBlank() ? null : normalized;
    }

    private String responseId() {
        return "resp_" + randomHex(28);
    }

    private String randomHex(int length) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        if (hex.length() >= length) {
            return hex.substring(0, length);
        }
        return hex;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String apiErrorText(String value) {
        return ApiErrorTextRedactor.redacted(value, redactor);
    }

    private boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        if (value instanceof CharSequence text) {
            return !text.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record ParseResult(List<Message> messages, String error, String code, String param) {
        static ParseResult success(List<Message> messages) {
            return new ParseResult(messages, null, null, null);
        }

        static ParseResult error(String error) {
            return new ParseResult(List.of(), error, null, null);
        }

        static ParseResult error(String error, String code, String param) {
            return new ParseResult(List.of(), error, code, param);
        }
    }

    public record OpenAiResponsesRequest(
        Object model,
        Object provider,
        Object input,
        Object instructions,
        @JsonProperty("previous_response_id") @JsonAlias("previousResponseId") String previousResponseId,
        String conversation,
        @JsonProperty("conversation_history") @JsonAlias("conversationHistory") Object conversationHistory,
        Object store,
        Object stream,
        List<OpenAiResponsesTool> tools,
        @JsonProperty("model_options") @JsonAlias("modelOptions") Object modelOptions,
        @JsonProperty("max_output_tokens")
        @JsonAlias({"maxOutputTokens", "max_tokens", "maxTokens"})
        Integer maxOutputTokens,
        String truncation
    ) {}

    public record OpenAiResponsesTool(
        String type,
        String name,
        String description,
        Map<String, Object> parameters,
        Boolean strict,
        OpenAiChatRequest.OpenAiFunction function
    ) {}
}
