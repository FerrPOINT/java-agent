package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.api.dto.OpenAiChatResponse;
import com.azhukov.agent.api.dto.OpenAiStreamError;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.security.ApiErrorTextRedactor;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.api.mapper.OpenAiMapper;
import com.azhukov.agent.service.ApiRunAdmissionService;
import com.azhukov.agent.service.OpenAiIdempotencyCache;
import com.azhukov.agent.service.OpenAiSessionService;
import com.azhukov.agent.service.OpenAiSessionService.OpenAiSessionContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping({"/v1/chat/completions", "/p/{profile}/v1/chat/completions"})
@RequiredArgsConstructor
public class ChatCompletionsController {

    private final AgentRuntime agentRuntime;
    private final ToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final OpenAiMapper openAiMapper;
    private final AgentProperties properties;
    private final OpenAiSessionService openAiSessionService;
    private final OpenAiIdempotencyCache idempotencyCache;
    private final Redactor redactor;
    private final ApiRunAdmissionService runAdmissionService;

    @PostMapping
    public Object completions(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = OpenAiSessionService.SESSION_ID_HEADER, required = false) String sessionIdHeader,
            @RequestHeader(value = OpenAiSessionService.SESSION_KEY_HEADER, required = false) String sessionKeyHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ParsedChatRequest parsed = parseChatRequest(rawBody);
        if (parsed.error() != null) {
            return parsed.error();
        }
        OpenAiChatRequest request = parsed.request();
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return openAiError(HttpStatus.BAD_REQUEST, "Missing or invalid 'messages' field", "invalid_request_error");
        }
        if (!OpenAiRequestBooleans.coerce(request.stream(), false)) {
            return idempotencyCache.getOrCompute(
                idempotencyKey,
                () -> idempotencyCache.fingerprint(
                    "chat.completions",
                    objectMapper,
                    request,
                    sessionIdHeader,
                    sessionKeyHeader),
                () -> completionsUncached(request, sessionIdHeader, sessionKeyHeader)
            );
        }
        return completionsUncached(request, sessionIdHeader, sessionKeyHeader);
    }

    private ParsedChatRequest parseChatRequest(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return new ParsedChatRequest(
                null,
                openAiError(HttpStatus.BAD_REQUEST, "Invalid JSON in request body", "invalid_request_error"));
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            return new ParsedChatRequest(
                null,
                openAiError(HttpStatus.BAD_REQUEST, "Invalid JSON in request body", "invalid_request_error"));
        }
        if (body == null || !body.isObject()) {
            return new ParsedChatRequest(
                null,
                openAiError(HttpStatus.BAD_REQUEST, "Missing or invalid 'messages' field", "invalid_request_error"));
        }
        JsonNode messages = body.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            return new ParsedChatRequest(
                null,
                openAiError(HttpStatus.BAD_REQUEST, "Missing or invalid 'messages' field", "invalid_request_error"));
        }
        if (body instanceof ObjectNode objectBody) {
            removeNonTextField(objectBody, "model");
            removeNonTextField(objectBody, "provider");
            JsonNode modelOptions = objectBody.get("model_options");
            if (modelOptions != null && !modelOptions.isObject()) {
                objectBody.remove("model_options");
            }
        }
        try {
            OpenAiChatRequest request = objectMapper.readerFor(OpenAiChatRequest.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(body);
            return new ParsedChatRequest(request, null);
        } catch (IOException | IllegalArgumentException e) {
            return new ParsedChatRequest(
                null,
                openAiError(HttpStatus.BAD_REQUEST, "Invalid JSON in request body", "invalid_request_error"));
        }
    }

    private void removeNonTextField(ObjectNode body, String field) {
        JsonNode value = body.get(field);
        if (value != null && !value.isTextual()) {
            body.remove(field);
        }
    }

    private Object completionsUncached(OpenAiChatRequest request,
                                       String sessionIdHeader,
                                       String sessionKeyHeader) {
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
        IncomingMessages incoming = incomingMessages(request);
        if (incoming.error() != null) {
            return incoming.error();
        }
        List<Message> parsedMessages = incoming.messages();
        if (parsedMessages.isEmpty() || !hasTerminalVisiblePayload(parsedMessages.get(parsedMessages.size() - 1))) {
            return openAiError(HttpStatus.BAD_REQUEST, "No user message found in messages", "invalid_request_error");
        }
        String systemPrompt = requestSystemPrompt(request);
        OpenAiSessionContext sessionContext =
            openAiSessionService.resolveChatCompletions(
                sessionIdHeader,
                sessionKeyHeader,
                requestedModel,
                statelessSessionSeed(systemPrompt, parsedMessages));
        List<Message> incomingMessages = currentIncomingMessages(parsedMessages, sessionContext.continuationRequested());
        List<Message> messages = buildMessages(sessionContext, systemPrompt, incomingMessages);
        List<ToolDefinition> tools = buildTools(request);
        ModelRequestOptions requestOptions = requestModelOptions(request, requestedModel);
        if (OpenAiRequestBooleans.coerce(request.stream(), false)) {
            return streamCompletions(request, requestedModel, sessionContext, incomingMessages, messages, tools, requestOptions);
        }
        return syncCompletion(request, requestedModel, sessionContext, incomingMessages, messages, tools, requestOptions);
    }

    private ResponseEntity<?> syncCompletion(OpenAiChatRequest request,
                                             String requestedModel,
                                             OpenAiSessionContext sessionContext,
                                             List<Message> incomingMessages,
                                             List<Message> messages,
                                             List<ToolDefinition> tools,
                                             ModelRequestOptions requestOptions) {
        AgentCallResult result;
        var reservation = runAdmissionService.tryAcquire();
        if (reservation.isEmpty()) {
            return concurrencyLimitedResponse();
        }
        try (ApiRunAdmissionService.Reservation ignored = reservation.get()) {
            result = runAgent(messages, tools, requestOptions);
        } catch (Exception e) {
            return openAiError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error: " + e.getMessage(),
                "server_error");
        }
        ChatResponse response = result.response();
        openAiSessionService.persistTurn(sessionContext, incomingMessages, response, result.generatedMessages());
        HttpHeaders headers = responseHeaders(sessionContext);
        if (isHardFailure(result)) {
            return chatCompletionError(result, headers);
        }
        addIncompleteHeaders(headers, result);
        return ResponseEntity.ok()
            .headers(headers)
            .body(chatCompletionBody(requestedModel, response, finishReason(result, response)));
    }

    private ResponseEntity<?> streamCompletions(OpenAiChatRequest request,
                                                String requestedModel,
                                                OpenAiSessionContext sessionContext,
                                                List<Message> incomingMessages,
                                                List<Message> messages,
                                                List<ToolDefinition> tools,
                                                ModelRequestOptions requestOptions) {
        var reservation = runAdmissionService.tryAcquire();
        if (reservation.isEmpty()) {
            return concurrencyLimitedResponse();
        }
        SseEmitter emitter = new SseEmitter(600_000L);
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        String model = requestedModel;
        long created = Instant.now().getEpochSecond();

        CompletableFuture.runAsync(() -> {
            try {
                AgentCallResult result = runAgent(messages, tools, requestOptions);
                ChatResponse response = result.response();
                sendSse(emitter, createRoleEvent(id, model, created));
                sendToolProgressEvents(emitter, result.generatedMessages());
                if (response.content() != null && !response.content().isEmpty()) {
                    sendSse(emitter, createDeltaEvent(id, model, created, response.content(), null));
                }
                openAiSessionService.persistTurn(sessionContext, incomingMessages, response, result.generatedMessages());
                sendSse(emitter, createFinishEvent(id, model, created, finishReason(result, response)));
                sendSseDone(emitter);
                emitter.complete();
            } catch (Exception e) {
                sendSse(emitter, createErrorEvent(e.getMessage()));
                emitter.complete();
            } finally {
                reservation.get().close();
            }
        });

        return ResponseEntity.ok()
            .headers(sseResponseHeaders(sessionContext))
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    private AgentCallResult runAgent(List<Message> messages,
                                     List<ToolDefinition> tools,
                                     ModelRequestOptions requestOptions) {
        TurnResult turnResult = agentRuntime.runMessages(messages, tools, requestOptions);
        if (turnResult != null) {
            ChatResponse response = chatResponseFrom(turnResult);
            return new AgentCallResult(response, generatedMessages(messages.size(), turnResult, response),
                turnResult.completed(), turnResult.error());
        }
        ChatResponse response = agentRuntime.run(messages, tools, requestOptions);
        return new AgentCallResult(response != null ? response : ChatResponse.text(""), List.of(), true, null);
    }

    private ChatResponse chatResponseFrom(TurnResult result) {
        if (result == null) {
            return ChatResponse.text("");
        }
        for (int i = result.messages().size() - 1; i >= 0; i--) {
            Message message = result.messages().get(i);
            if (message.role() == Role.ASSISTANT
                && (message.toolCalls() == null || message.toolCalls().isEmpty())) {
                return ChatResponse.text(message.content());
            }
        }
        if (result.error() != null && !result.error().isBlank()) {
            return result.completed() ? ChatResponse.text(result.error()) : ChatResponse.text("");
        }
        return ChatResponse.text(result.finalText());
    }

    private List<Message> generatedMessages(int initialSize, TurnResult result, ChatResponse response) {
        if (result == null || result.messages().isEmpty()) {
            return List.of();
        }
        if (result.messages().size() > initialSize) {
            return List.copyOf(result.messages().subList(initialSize, result.messages().size()));
        }
        if (result.error() != null && response.content() != null && !response.content().isBlank()) {
            return List.of(Message.assistant(response.content(), 1));
        }
        return List.copyOf(result.messages());
    }

    private record AgentCallResult(ChatResponse response,
                                   List<Message> generatedMessages,
                                   boolean completed,
                                   String error) {}

    private record ToolProgressInfo(String id,
                                    String tool,
                                    String label,
                                    String emoji,
                                    Set<String> variants) {}

    private record ParsedChatRequest(
        OpenAiChatRequest request,
        ResponseEntity<Map<String, Object>> error
    ) {}

    private boolean isHardFailure(AgentCallResult result) {
        return result != null
            && (!result.completed() || (result.error() != null && !result.error().isBlank()))
            && (result.response().content() == null || result.response().content().isBlank())
            && !result.response().hasToolCalls();
    }

    private ResponseEntity<Map<String, Object>> chatCompletionError(AgentCallResult result, HttpHeaders headers) {
        addIncompleteHeaders(headers, result);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", result.error() != null && !result.error().isBlank()
            ? apiErrorText(result.error())
            : "Agent run did not produce a response.");
        error.put("type", "server_error");
        error.put("code", "agent_incomplete");
        error.put("hermes", Map.of(
            "completed", result.completed(),
            "partial", !result.completed(),
            "failed", result.error() != null && !result.error().isBlank()
        ));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .headers(headers)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("error", error));
    }

    private void addIncompleteHeaders(HttpHeaders headers, AgentCallResult result) {
        if (result == null || (result.completed() && (result.error() == null || result.error().isBlank()))) {
            return;
        }
        headers.add("X-Hermes-Completed", "false");
        headers.add("X-Hermes-Partial", Boolean.toString(!result.completed()));
        if (result.error() != null && !result.error().isBlank()) {
            headers.add("X-Hermes-Error", errorHeader(apiErrorText(result.error())));
        }
    }

    private String errorHeader(String error) {
        String normalized = error.replace('\r', ' ').replace('\n', ' ');
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }

    private String finishReason(AgentCallResult result, ChatResponse response) {
        if (result != null && (!result.completed() || (result.error() != null && !result.error().isBlank()))) {
            String error = result.error();
            if (error != null && error.toLowerCase(java.util.Locale.ROOT).contains("truncat")) {
                return "length";
            }
            return "error";
        }
        return response != null && response.hasToolCalls() ? "tool_calls" : "stop";
    }

    private OpenAiChatResponse chatCompletionBody(String model, ChatResponse response, String finishReason) {
        OpenAiChatResponse body = openAiMapper.toOpenAiResponse(model, response);
        List<OpenAiChatResponse.Choice> choices = body.choices().stream()
            .map(choice -> new OpenAiChatResponse.Choice(choice.index(), choice.message(), finishReason))
            .toList();
        return new OpenAiChatResponse(body.id(), body.object(), body.created(), body.model(), choices, body.usage());
    }

    private ModelRequestOptions requestModelOptions(OpenAiChatRequest request, String requestedModel) {
        return OpenAiRequestModelOptions.from(
            properties,
            requestedModel,
            request.provider(),
            request.modelOptions(),
            request.maxTokens(),
            false,
            false);
    }

    private String requestedModel(String requestModel) {
        if (requestModel != null && !requestModel.isBlank()) {
            return requestModel.trim();
        }
        String advertisedModel = OpenAiModelRouting.advertisedModel(properties);
        return advertisedModel != null && !advertisedModel.isBlank() ? advertisedModel : "unknown";
    }

    private List<Message> buildMessages(OpenAiSessionContext sessionContext,
                                        String systemPrompt,
                                        List<Message> incomingMessages) {
        Session session = sessionContext.session();
        List<Message> messages = new ArrayList<>();
        messages.add(promptBuilder.buildSystemMessage(session, systemPrompt));
        if (sessionContext.continuationRequested()) {
            messages.addAll(openAiSessionService.historyFor(sessionContext));
        }
        messages.addAll(incomingMessages);
        return messages;
    }

    private String statelessSessionSeed(String systemPrompt, List<Message> parsedMessages) {
        String firstUserMessage = "";
        if (parsedMessages != null) {
            for (Message message : parsedMessages) {
                if (message != null && message.role() == Role.USER) {
                    firstUserMessage = message.content() != null ? message.content() : "";
                    break;
                }
            }
        }
        return (systemPrompt != null ? systemPrompt : "") + "\n" + firstUserMessage;
    }

    private IncomingMessages incomingMessages(OpenAiChatRequest request) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < request.messages().size(); i++) {
            OpenAiChatRequest.OpenAiMessage m = request.messages().get(i);
            if (isSystemMessage(m)) {
                continue;
            }
            if (!isConversationMessage(m)) {
                continue;
            }
            try {
                messages.add(openAiMapper.toMessage(m));
            } catch (IllegalArgumentException e) {
                return new IncomingMessages(List.of(), openAiError(
                    HttpStatus.BAD_REQUEST,
                    e.getMessage(),
                    "invalid_request_error",
                    OpenAiContentNormalizer.errorCode(e),
                "messages[" + i + "].content"));
            }
        }
        return new IncomingMessages(messages, null);
    }

    private List<Message> currentIncomingMessages(List<Message> messages, boolean continuationRequested) {
        if (continuationRequested && messages != null && !messages.isEmpty()) {
            return List.of(messages.get(messages.size() - 1));
        }
        return messages;
    }

    private record IncomingMessages(List<Message> messages, ResponseEntity<Map<String, Object>> error) {}

    private boolean hasTerminalVisiblePayload(Message message) {
        return message != null
            && ((message.content() != null && !message.content().isBlank())
                || (message.imageCount() != null && message.imageCount() > 0));
    }

    private HttpHeaders responseHeaders(OpenAiSessionContext sessionContext) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(OpenAiSessionService.SESSION_ID_HEADER, sessionContext.responseSessionId());
        if (sessionContext.sessionKey() != null && !sessionContext.sessionKey().isBlank()) {
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

    private String requestSystemPrompt(OpenAiChatRequest request) {
        StringBuilder prompt = new StringBuilder();
        for (OpenAiChatRequest.OpenAiMessage message : request.messages()) {
            if (!isSystemMessage(message)) {
                continue;
            }
            String content = OpenAiContentNormalizer.normalizeSystemText(message.content());
            if (content.isBlank()) {
                continue;
            }
            if (!prompt.isEmpty()) {
                prompt.append("\n");
            }
            prompt.append(content);
        }
        return prompt.toString();
    }

    private boolean isSystemMessage(OpenAiChatRequest.OpenAiMessage message) {
        if (message == null || message.role() == null) {
            return false;
        }
        return "system".equals(message.role()) || "developer".equals(message.role());
    }

    private boolean isConversationMessage(OpenAiChatRequest.OpenAiMessage message) {
        if (message == null || message.role() == null) {
            return false;
        }
        return "user".equals(message.role()) || "assistant".equals(message.role());
    }

    private List<ToolDefinition> buildTools(OpenAiChatRequest request) {
        if (request.tools() != null) {
            return request.tools().stream()
                .map(openAiMapper::toToolDefinition)
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

    private void sendSse(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event()
                .data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendNamedSse(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                .name(eventName)
                .data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendToolProgressEvents(SseEmitter emitter, List<Message> generatedMessages) {
        if (generatedMessages == null || generatedMessages.isEmpty()) {
            return;
        }
        Map<String, ToolProgressInfo> startedByVariant = new LinkedHashMap<>();
        for (Message message : generatedMessages) {
            if (message.role() == Role.ASSISTANT) {
                for (ToolCall call : nonInternalToolCalls(message.toolCalls())) {
                    Set<String> variants = ToolCall.idVariants(call);
                    if (variants.isEmpty()) {
                        continue;
                    }
                    ToolProgressInfo info = new ToolProgressInfo(
                        call.id(),
                        call.name(),
                        toolProgressLabel(call),
                        toolEmoji(call.name()),
                        variants);
                    variants.forEach(variant -> startedByVariant.putIfAbsent(variant, info));
                    sendNamedSse(emitter, "hermes.tool.progress", toolProgressPayload(info, "running"));
                }
                continue;
            }
            if (message.role() != Role.TOOL) {
                continue;
            }
            ToolProgressInfo info = startedByVariant.get(message.toolCallId());
            if (info == null) {
                continue;
            }
            sendNamedSse(emitter, "hermes.tool.progress", toolProgressPayload(info, "completed"));
            info.variants().forEach(startedByVariant::remove);
            ToolCall.idVariants(message.toolCallId()).forEach(startedByVariant::remove);
        }
    }

    private List<ToolCall> nonInternalToolCalls(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        return calls.stream()
            .filter(call -> call != null
                && call.id() != null
                && !call.id().isBlank()
                && call.name() != null
                && !call.name().isBlank()
                && !call.name().startsWith("_"))
            .toList();
    }

    private Map<String, Object> toolProgressPayload(ToolProgressInfo info, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", info.tool());
        if ("running".equals(status)) {
            payload.put("emoji", info.emoji());
            payload.put("label", info.label());
        }
        payload.put("toolCallId", info.id());
        payload.put("status", status);
        return payload;
    }

    private String toolProgressLabel(ToolCall call) {
        String label = primaryToolArgument(call);
        if (label == null || label.isBlank()) {
            label = call.name();
        }
        label = collapseWhitespace(apiErrorText(label));
        return label.length() > 120 ? label.substring(0, 117) + "..." : label;
    }

    private String primaryToolArgument(ToolCall call) {
        JsonNode args = parseToolArguments(call.arguments());
        if (args == null || !args.isObject()) {
            return null;
        }
        List<String> preferredKeys = switch (call.name()) {
            case "terminal" -> List.of("command", "cmd");
            case "process", "cronjob" -> List.of("action");
            case "web_search" -> List.of("query");
            case "web_extract" -> List.of("urls", "url");
            case "read_file", "write_file", "patch" -> List.of("path", "file", "filepath");
            case "search_files" -> List.of("pattern", "query");
            case "browser_navigate" -> List.of("url");
            case "browser_click" -> List.of("ref", "selector", "text");
            case "browser_type" -> List.of("text", "selector");
            case "execute_code", "browser_exec" -> List.of("code");
            case "delegate_task" -> List.of("goal", "action");
            case "clarify" -> List.of("question");
            case "skill_view", "skill_manage" -> List.of("name");
            case "skills_list" -> List.of("category");
            case "memory" -> List.of("content", "old_text", "action");
            case "todo" -> List.of("todos", "action");
            case "image_generate" -> List.of("prompt");
            case "text_to_speech" -> List.of("text");
            case "vision_analyze" -> List.of("question");
            default -> List.of("query", "command", "path", "url", "text", "prompt", "action");
        };
        for (String key : preferredKeys) {
            JsonNode value = args.get(key);
            String text = compactJsonValue(value);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return StreamSupport.stream(args.spliterator(), false)
            .map(this::compactJsonValue)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    private JsonNode parseToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(arguments);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String compactJsonValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        if (value.isArray()) {
            if (value.isEmpty()) {
                return null;
            }
            return StreamSupport.stream(value.spliterator(), false)
                .map(this::compactJsonValue)
                .filter(text -> text != null && !text.isBlank())
                .limit(3)
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
        }
        if (value.isObject()) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return value.toString();
            }
        }
        return value.asText(null);
    }

    private String collapseWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private String toolEmoji(String toolName) {
        return switch (toolName) {
            case "terminal" -> "💻";
            case "process", "cronjob" -> "⚙️";
            case "web_search", "web_extract" -> "🔎";
            case "read_file" -> "📖";
            case "write_file", "patch" -> "✏️";
            case "search_files" -> "🔍";
            case "browser_navigate", "browser_click", "browser_type", "browser_console", "browser_cdp" -> "🌐";
            case "execute_code" -> "⌨️";
            case "delegate_task" -> "🤖";
            case "memory", "session_search" -> "🧠";
            case "todo" -> "☑️";
            case "image_generate", "vision_analyze" -> "🖼️";
            case "text_to_speech" -> "🔊";
            default -> "⚡";
        };
    }

    private void sendSseDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                .data("[DONE]"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private Map<String, Object> createRoleEvent(String id, String model, long created) {
        return createChunk(id, model, created, Map.of("role", "assistant"), null);
    }

    private Map<String, Object> createDeltaEvent(String id, String model, long created, String token, List<ToolCall> toolCalls) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (token != null) {
            delta.put("content", token);
        }
        List<Map<String, Object>> openAiToolCalls = toOpenAiToolCalls(toolCalls);
        if (openAiToolCalls != null && !openAiToolCalls.isEmpty()) {
            delta.put("tool_calls", openAiToolCalls);
        }
        return createChunk(id, model, created, delta, null);
    }

    private Map<String, Object> createFinishEvent(String id, String model, long created, String finishReason) {
        return createChunk(id, model, created, Map.of(), finishReason);
    }

    private OpenAiStreamError createErrorEvent(String message) {
        return new OpenAiStreamError("streaming_error", apiErrorText(message));
    }

    private Map<String, Object> createChunk(String id, String model, long created, Map<String, Object> delta, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));
        return chunk;
    }

    private List<Map<String, Object>> toOpenAiToolCalls(List<ToolCall> calls) {
        if (calls == null) return null;
        return calls.stream()
            .map(c -> {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", c.name());
                function.put("arguments", c.arguments());
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", c.id() != null ? c.id() : UUID.randomUUID().toString());
                call.put("type", "function");
                call.put("function", function);
                return call;
            })
            .toList();
    }

    private String apiErrorText(String value) {
        if (value == null) {
            return null;
        }
        return ApiErrorTextRedactor.redacted(value, redactor);
    }

}
