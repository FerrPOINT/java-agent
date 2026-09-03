package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.api.mapper.OpenAiMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.security.ApiErrorTextRedactor;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.service.ApiRunAdmissionService;
import com.azhukov.agent.service.OpenAiResponseStore;
import com.azhukov.agent.service.OpenAiResponseStore.StoredResponse;
import com.azhukov.agent.service.OpenAiRunService;
import com.azhukov.agent.service.OpenAiRunService.ControlResult;
import com.azhukov.agent.service.OpenAiRunService.RunRecord;
import com.azhukov.agent.service.OpenAiSessionService;
import com.azhukov.agent.service.OpenAiSessionService.OpenAiSessionContext;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/v1/runs", "/p/{profile}/v1/runs"})
@RequiredArgsConstructor
public class OpenAiRunsController {

    private final OpenAiRunService runService;
    private final OpenAiSessionService openAiSessionService;
    private final OpenAiResponseStore responseStore;
    private final OpenAiMapper openAiMapper;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final Redactor redactor;
    private final ApiRunAdmissionService runAdmissionService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRun(
            @RequestBody OpenAiRunRequest request,
            @RequestHeader(value = OpenAiSessionService.SESSION_KEY_HEADER, required = false) String sessionKeyHeader) {
        if (request == null || !runsInputTruthy(request.input())) {
            return openAiError(HttpStatus.BAD_REQUEST, "Missing 'input' field", "invalid_request_error");
        }

        RunInput input = parseInput(request.input());
        if (input.error() != null) {
            return openAiError(HttpStatus.BAD_REQUEST, input.error(), "invalid_request_error",
                input.code(), input.param());
        }
        if (input.userMessage() == null
            || input.userMessage().content() == null
            || input.userMessage().content().isEmpty()) {
            return openAiError(HttpStatus.BAD_REQUEST, "No user message found in input", "invalid_request_error");
        }

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
        String instructions = normalizeInstructions(request.instructions());
        StoredResponse previous = previousResponse(request.previousResponseId());
        if (previous != null && instructions == null) {
            instructions = previous.instructions();
        }

        ParseResult parsedHistory = historyToPersist(request, input, previous);
        if (parsedHistory.error() != null) {
            return openAiError(HttpStatus.BAD_REQUEST, parsedHistory.error(), "invalid_request_error",
                parsedHistory.code(), parsedHistory.param());
        }
        List<Message> historyToPersist = parsedHistory.messages();
        OpenAiSessionContext sessionContext;
        try {
            if (hasText(request.sessionId())) {
                sessionContext = openAiSessionService.resolveRunSession(
                    request.sessionId(),
                    sessionKeyHeader,
                    requestedModel);
            } else {
                UUID sessionId = previous != null ? previous.sessionId() : null;
                sessionContext = openAiSessionService.resolveRunSession(sessionId, sessionKeyHeader, requestedModel);
            }
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Session not found:")) {
                return openAiError(HttpStatus.NOT_FOUND, e.getMessage(), "invalid_request_error", "session_not_found");
            }
            return openAiError(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid_request_error");
        }

        boolean previousHistoryAlreadyPersisted = previous != null
            && previous.sessionId() != null
            && previous.sessionId().equals(sessionContext.session().id())
            && !hasExplicitConversationHistory(request);
        var reservation = runAdmissionService.tryAcquire();
        if (reservation.isEmpty()) {
            return concurrencyLimitedResponse();
        }
        ApiRunAdmissionService.Reservation acquiredReservation = reservation.get();
        RunRecord run;
        try {
            run = runService.submit(
                sessionContext,
                input.userMessage(),
                requestedModel,
                requestOptions(request, requestedModel),
                instructions,
                previousHistoryAlreadyPersisted ? List.of() : historyToPersist,
                acquiredReservation
            );
        } catch (RuntimeException e) {
            acquiredReservation.close();
            throw e;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", run.runId());
        body.put("status", "started");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .headers(responseHeaders(sessionContext))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<Map<String, Object>> getRun(@PathVariable String runId) {
        RunRecord run = runService.get(runId);
        if (run == null) {
            return openAiError(HttpStatus.NOT_FOUND, "Run not found: " + runId, "invalid_request_error", "run_not_found");
        }
        return ResponseEntity.ok(run.snapshot());
    }

    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> runEvents(@PathVariable String runId) {
        SseEmitter emitter = runService.events(runId);
        if (emitter == null) {
            return openAiError(HttpStatus.NOT_FOUND, "Run not found: " + runId, "invalid_request_error", "run_not_found");
        }
        return ResponseEntity.ok()
            .headers(sseHeaders())
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    @PostMapping("/{runId}/approval")
    public ResponseEntity<Map<String, Object>> approval(@PathVariable String runId,
                                                        @RequestBody(required = false) String body) {
        if (runService.get(runId) == null) {
            return openAiError(HttpStatus.NOT_FOUND, "Run not found: " + runId,
                "invalid_request_error", "run_not_found");
        }
        BodyParseResult parsedBody = parseControlBody(body, "Invalid JSON");
        if (parsedBody.error() != null) {
            return parsedBody.error();
        }
        Map<String, Object> requestBody = parsedBody.body();
        String choice = stringValue(requestBody.get("choice"));
        boolean resolveAll = OpenAiRequestBooleans.coerce(requestBody.get("all"), false)
            || OpenAiRequestBooleans.coerce(requestBody.get("resolve_all"), false);
        return controlResponse(runId, runService.approval(runId, choice, resolveAll));
    }

    @PostMapping("/{runId}/steer")
    public ResponseEntity<Map<String, Object>> steer(@PathVariable String runId,
                                                     @RequestBody(required = false) String body) {
        RunRecord run = runService.get(runId);
        if (run == null) {
            return openAiError(HttpStatus.NOT_FOUND, "Run not found: " + runId,
                "invalid_request_error", "run_not_found");
        }
        if (!"running".equals(run.status())) {
            return openAiError(HttpStatus.CONFLICT,
                "Run is not currently accepting steer input: " + runId,
                "invalid_request_error",
                "run_not_accepting_steer");
        }
        BodyParseResult parsedBody = parseControlBody(body, "Invalid JSON in request body");
        if (parsedBody.error() != null) {
            return parsedBody.error();
        }
        Map<String, Object> requestBody = parsedBody.body();
        Object raw = firstTruthyRequestValue(requestBody, "input", "message", "text");
        String text;
        try {
            text = OpenAiContentNormalizer.normalizeSystemText(raw).trim();
        } catch (IllegalArgumentException e) {
            return openAiError(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid_request_error", "invalid_steer_input");
        }
        return controlResponse(runId, runService.steer(runId, text));
    }

    @PostMapping("/{runId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String runId) {
        return controlResponse(runId, runService.stop(runId));
    }

    private ResponseEntity<Map<String, Object>> controlResponse(String runId, ControlResult result) {
        if (result.status() == 200) {
            return ResponseEntity.ok(result.body());
        }
        HttpStatus status = HttpStatus.valueOf(result.status());
        String message = "Run not found".equals(result.message())
            ? "Run not found: " + runId
            : result.message();
        return openAiError(status, message, "invalid_request_error", result.code());
    }

    private StoredResponse previousResponse(String previousResponseId) {
        if (previousResponseId == null || previousResponseId.isBlank()) {
            return null;
        }
        return responseStore.get(previousResponseId.trim());
    }

    private ParseResult historyToPersist(OpenAiRunRequest request, RunInput input, StoredResponse previous) {
        if (hasExplicitConversationHistory(request)) {
            return parseHistory(request.conversationHistory());
        }
        if (previous != null) {
            return ParseResult.success(previous.conversationHistory());
        }
        return ParseResult.success(input.priorMessages());
    }

    private boolean hasExplicitConversationHistory(OpenAiRunRequest request) {
        return runsInputTruthy(request.conversationHistory());
    }

    private RunInput parseInput(Object rawInput) {
        if (rawInput instanceof String text) {
            return RunInput.success(List.of(), Message.user(OpenAiContentNormalizer.normalizeConversationText(text)));
        }
        if (!(rawInput instanceof List<?> list)) {
            return RunInput.error("'input' must be a string or array");
        }
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ParseResult parsed = parseInputItem(list.get(i), "input[" + i + "]");
            if (parsed.error() != null) {
                return RunInput.error(parsed.error(), parsed.code(), parsed.param());
            }
            messages.addAll(parsed.messages());
        }
        if (messages.isEmpty()) {
            return RunInput.error("No user message found in input");
        }
        Message last = messages.get(messages.size() - 1);
        List<Message> prior = messages.size() > 1
            ? List.copyOf(messages.subList(0, messages.size() - 1))
            : List.of();
        return RunInput.success(prior, asUserMessage(last));
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
            } catch (JsonProcessingException ignored) {
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

    private Message asUserMessage(Message message) {
        String text = message != null && message.content() != null ? message.content() : "";
        int imageCount = message != null && message.imageCount() != null ? message.imageCount() : 0;
        return imageCount > 0 ? Message.userWithImages(text, imageCount) : Message.user(text);
    }

    private ModelRequestOptions requestOptions(OpenAiRunRequest request, String requestedModel) {
        return OpenAiRequestModelOptions.from(
            properties,
            requestedModel,
            request.provider(),
            request.modelOptions(),
            request.maxOutputTokens(),
            true,
            false
        );
    }

    private HttpHeaders responseHeaders(OpenAiSessionContext sessionContext) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(OpenAiSessionService.SESSION_ID_HEADER, sessionContext.responseSessionId());
        if (hasText(sessionContext.sessionKey())) {
            headers.add(OpenAiSessionService.SESSION_KEY_HEADER, sessionContext.sessionKey());
        }
        return headers;
    }

    private HttpHeaders sseHeaders() {
        HttpHeaders headers = new HttpHeaders();
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

    private ResponseEntity<Map<String, Object>> openAiError(HttpStatus status,
                                                            String message,
                                                            String type,
                                                            String code) {
        return openAiError(status, message, type, code, null);
    }

    private ResponseEntity<Map<String, Object>> openAiError(HttpStatus status,
                                                            String message,
                                                            String type,
                                                            String code,
                                                            String param) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", apiErrorText(message));
        error.put("type", type);
        error.put("param", param != null && !param.isBlank() ? param : null);
        error.put("code", code != null && !code.isBlank() ? code : null);
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("error", error));
    }

    private String apiErrorText(String value) {
        return ApiErrorTextRedactor.redacted(value, redactor);
    }

    private String requestedModel(Object requestModel) {
        if (requestModel instanceof String text && hasText(text)) {
            return text.trim();
        }
        String advertisedModel = OpenAiModelRouting.advertisedModel(properties);
        return hasText(advertisedModel) ? advertisedModel : "unknown";
    }

    private String normalizeInstructions(Object instructions) {
        if (instructions == null) {
            return null;
        }
        String normalized = OpenAiContentNormalizer.normalizeSystemText(instructions);
        return normalized.isBlank() ? null : normalized;
    }

    private boolean runsInputTruthy(Object input) {
        if (input == null) {
            return false;
        }
        if (input instanceof Boolean bool) {
            return bool;
        }
        if (input instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        if (input instanceof CharSequence text) {
            return !text.isEmpty();
        }
        if (input instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (input instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private Object firstTruthyRequestValue(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object value = body.get(key);
            if (runsInputTruthy(value)) {
                return value;
            }
        }
        return "";
    }

    private BodyParseResult parseControlBody(String rawBody, String invalidJsonMessage) {
        if (rawBody == null || rawBody.isBlank()) {
            return BodyParseResult.error(openAiError(HttpStatus.BAD_REQUEST,
                invalidJsonMessage, "invalid_request_error"));
        }
        Object parsed;
        try {
            parsed = objectMapper.readValue(rawBody, Object.class);
        } catch (JsonProcessingException e) {
            return BodyParseResult.error(openAiError(HttpStatus.BAD_REQUEST,
                invalidJsonMessage, "invalid_request_error"));
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return BodyParseResult.error(openAiError(HttpStatus.BAD_REQUEST,
                "Request body must be a JSON object", "invalid_request_error"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) map;
        return BodyParseResult.success(body);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record RunInput(List<Message> priorMessages, Message userMessage, String error, String code, String param) {
        static RunInput success(List<Message> priorMessages, Message userMessage) {
            return new RunInput(priorMessages, userMessage, null, null, null);
        }

        static RunInput error(String error) {
            return new RunInput(List.of(), null, error, null, null);
        }

        static RunInput error(String error, String code, String param) {
            return new RunInput(List.of(), null, error, code, param);
        }
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

    private record BodyParseResult(Map<String, Object> body, ResponseEntity<Map<String, Object>> error) {
        static BodyParseResult success(Map<String, Object> body) {
            return new BodyParseResult(body, null);
        }

        static BodyParseResult error(ResponseEntity<Map<String, Object>> error) {
            return new BodyParseResult(Map.of(), error);
        }
    }

    public record OpenAiRunRequest(
        Object model,
        Object provider,
        Object input,
        Object instructions,
        @JsonProperty("previous_response_id") @JsonAlias("previousResponseId") String previousResponseId,
        @JsonProperty("session_id") @JsonAlias("sessionId") String sessionId,
        @JsonProperty("conversation_history") @JsonAlias("conversationHistory") Object conversationHistory,
        @JsonProperty("model_options") @JsonAlias("modelOptions") Object modelOptions,
        @JsonProperty("max_output_tokens")
        @JsonAlias({"maxOutputTokens", "max_tokens", "maxTokens"})
        Integer maxOutputTokens
    ) {}
}
