package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.shared.http.ChatEventStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Per-domain delegate covering messaging endpoints:
 * chat (sync), chat (SSE streaming), TTS synthesis and audio transcription.
 */
@Service
@Slf4j
public class MessageApiClient extends BaseBackendClient {

    private final com.azhukov.agent.bot.config.BotProperties properties;

    public MessageApiClient(@Qualifier("streamingBackendRestClient") RestClient restClient, ObjectMapper objectMapper,
                            com.azhukov.agent.bot.config.BotProperties properties) {
        super(restClient, objectMapper);
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Synchronous chat
    // ------------------------------------------------------------------

    /**
     * Send a chat message to the agent backend, optionally carrying runtime flags
     * from a Telegram bot session (fast mode, reasoning effort, voice mode, etc.).
     */
    public AgentBackendClient.ChatResult chat(String message, String sessionId, BotSessionEntity runtime) {
        return chat(message, sessionId, runtime, null);
    }

    /** Chat with inbound attachment references (WP-11 artifact ids). */
    public AgentBackendClient.ChatResult chat(String message, String sessionId, BotSessionEntity runtime,
                                              java.util.List<java.util.Map<String, String>> attachments) {
        Map<String, Object> body = buildChatBody(message, sessionId, runtime);
        if (attachments != null && !attachments.isEmpty()) {
            body.put("attachments", attachments);
        }

        try {
            String responseJson = restClient.post()
                .uri("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            if (responseJson == null || responseJson.isBlank()) {
                log.warn("Backend chat returned empty response for sessionId={}", sessionId);
                return new AgentBackendClient.ChatResult("Error: empty response from backend");
            }

            JsonNode node = objectMapper.readTree(responseJson);
            // h10: parse through the shared DTO — field names and types are the
            // contract now, not hand-rolled JsonNode walking (which had already
            // drifted once: "response" vs "content" fallback). The reader is
            // explicitly tolerant: legacy builds send extra fields ("response")
            // and the injected mapper may be strict.
            com.azhukov.agent.shared.dto.ChatResponseDto dto;
            try {
                dto = objectMapper.readerFor(com.azhukov.agent.shared.dto.ChatResponseDto.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(node);
            } catch (Exception parseEx) {
                log.warn("Backend chat response does not match ChatResponseDto: {}", responseJson);
                return new AgentBackendClient.ChatResult("Error: malformed backend reply");
            }
            String responseText = dto.content();
            if (responseText == null) {
                // Legacy fallback: old backend builds sent "response" instead of "content"
                JsonNode responseField = node.get("response");
                if (responseField != null && !responseField.isNull()) {
                    responseText = responseField.asText();
                }
            }
            if (responseText == null) {
                log.warn("Backend chat response missing 'content'/'response' field: {}", responseJson);
                return new AgentBackendClient.ChatResult("Error: missing 'response' field in backend reply");
            }

            return new AgentBackendClient.ChatResult(responseText, dto.modelUsed(),
                dto.contextTokens(), dto.contextLength(), false, dto.memoryUpdated(), dto.sessionId());
        } catch (Exception e) {
            log.warn("Backend chat failed for sessionId={}: {}", sessionId, e.getMessage());
            return new AgentBackendClient.ChatResult("Error: " + e.getMessage());
        }
    }

    /** Backward-compatible overload without runtime flags. */
    public AgentBackendClient.ChatResult chat(String message, String sessionId) {
        return chat(message, sessionId, null);
    }

    // ------------------------------------------------------------------
    // Streaming chat (SSE)
    // ------------------------------------------------------------------

    /**
     * Send a chat message to the agent backend and stream the response back
     * via Server-Sent Events, optionally carrying runtime flags from a Telegram bot session.
     *
     * <p>This is a <strong>blocking</strong> call — the caller should run it
     * in a separate thread.
     */
    public AgentBackendClient.ChatResult chatStream(String message,
                                                    String sessionId,
                                                    BotSessionEntity runtime,
                                                    Consumer<String> tokenConsumer,
                                                    Consumer<String> toolCallConsumer,
                                                    java.util.function.BiConsumer<String, String> toolResultConsumer,
                                                    Consumer<String> retryConsumer,
                                                    Consumer<String> reviewConsumer,
                                                    Consumer<String> clarifyConsumer,
                                                    Consumer<AgentBackendClient.ChatResult> onComplete,
                                                    Consumer<Throwable> onError) {
        return chatStream(message, sessionId, runtime, null, tokenConsumer, toolCallConsumer,
            toolResultConsumer, retryConsumer, reviewConsumer, clarifyConsumer, onComplete, onError);
    }

    /** WP-11 overload carrying inbound attachment artifact ids on the chat request. */
    public AgentBackendClient.ChatResult chatStream(String message,
                                                    String sessionId,
                                                    BotSessionEntity runtime,
                                                    java.util.List<java.util.Map<String, String>> attachments,
                                                    Consumer<String> tokenConsumer,
                                                    Consumer<String> toolCallConsumer,
                                                    java.util.function.BiConsumer<String, String> toolResultConsumer,
                                                    Consumer<String> retryConsumer,
                                                    Consumer<String> reviewConsumer,
                                                    Consumer<String> clarifyConsumer,
                                                    Consumer<AgentBackendClient.ChatResult> onComplete,
                                                    Consumer<Throwable> onError) {
        Map<String, Object> body = buildChatBody(message, sessionId, runtime);
        if (attachments != null && !attachments.isEmpty()) {
            body.put("attachments", attachments);
        }

        StringBuilder accumulated = new StringBuilder();
        AgentBackendClient.ChatResult[] metadataHolder = new AgentBackendClient.ChatResult[1];

        try {
            // A failure before the response headers does not establish that the POST was rejected.
            InputStream is = restClient.post()
                .uri("/api/v1/agent/chat/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(InputStream.class);
            ChatEventStream.read(is, objectMapper, event -> {
                String type = event.path("type").asText("");
                if ("metadata".equalsIgnoreCase(type)) {
                    metadataHolder[0] = extractMetadata(event);
                    return;
                }
                // tool_calls event — LLM decided to call tools.
                // Do NOT send a progress bubble here; the tool_start event
                // (fired per-tool right before execution) will send the bubble.
                // Sending here causes duplicate bubbles in Telegram.
                if ("tool_calls".equalsIgnoreCase(type)) {
                    return;
                }
                // tool_start event — notify tool call consumer
                if ("tool_start".equalsIgnoreCase(type)) {
                    String toolName = event.path("toolName").asText("");
                    if (!toolName.isEmpty()) {
                        // Extract arguments from toolCalls array (added in AgentStreamingService)
                        String toolArgs = "";
                        JsonNode toolCallsNode = event.get("toolCalls");
                        if (toolCallsNode != null && toolCallsNode.isArray() && toolCallsNode.size() > 0) {
                            JsonNode tc = toolCallsNode.get(0);
                            toolArgs = tc.path("arguments").asText("");
                            if (toolArgs.isEmpty()) {
                                JsonNode argsNode = tc.path("arguments");
                                if (argsNode != null && !argsNode.isNull()) {
                                    toolArgs = argsNode.toString();
                                }
                            }
                        }
                        toolCallConsumer.accept(toolName + "\u0001" + toolArgs);
                    }
                    return;
                }
                // tool_result event — notify tool result consumer
                if ("tool_result".equalsIgnoreCase(type)) {
                    String toolName = event.path("toolName").asText("");
                    String toolResult = event.path("toolResult").asText("");
                    if (!toolName.isEmpty()) {
                        toolResultConsumer.accept(toolName, toolResult);
                    }
                    return;
                }
                // retry event — notify retry consumer so the bot can show retry status
                if ("retry".equalsIgnoreCase(type)) {
                    String retryMsg = event.path("error").asText(
                        event.path("message").asText("Retrying..."));
                    retryConsumer.accept(retryMsg);
                    return;
                }
                // continuation event — also surface as retry status
                if ("continuation".equalsIgnoreCase(type)) {
                    String contMsg = event.path("error").asText(
                        event.path("message").asText("Continuing..."));
                    retryConsumer.accept(contMsg);
                    return;
                }
                // commentary event — the text was already streamed via token events,
                // so skip it to avoid duplicating the text in the output.
                if ("commentary".equalsIgnoreCase(type)) {
                    return;
                }
                // review event (Hermes parity: background_review_callback)
                // — "💾 Self-improvement review: …" summary from an
                // earlier turn's background review. Delivered to the
                // chat AFTER the main response (pending-release).
                if ("review".equalsIgnoreCase(type)) {
                    String reviewMsg = event.path("review").asText(null);
                    if (reviewMsg == null || reviewMsg.isBlank()) {
                        reviewMsg = event.path("summary").asText(null);
                    }
                    if (reviewMsg == null || reviewMsg.isBlank()) {
                        reviewMsg = event.path("message").asText(null);
                    }
                    if (reviewMsg == null || reviewMsg.isBlank()) {
                        reviewMsg = event.path("error").asText(null);
                    }
                    if (reviewMsg != null && !reviewMsg.isBlank() && reviewConsumer != null) {
                        reviewConsumer.accept(reviewMsg);
                    }
                    return;
                }
                // clarify event (Hermes clarify_gateway parity): blocking
                // clarify prompt from the backend — payload JSON in `error`.
                if ("clarify".equalsIgnoreCase(type)) {
                    String payload = event.path("error").asText(null);
                    String clarifySessionId = event.path("sessionId").asText(sessionId);
                    if (payload != null && !payload.isEmpty() && clarifyConsumer != null) {
                        log.info("clarify_sse_received requestSession={} clarifySession={} payloadBytes={}",
                            sessionId, clarifySessionId, payload.length());
                        clarifyConsumer.accept(clarifySessionId + "\u0001" + payload);
                    }
                    return;
                }
                JsonNode tokenNode = event.get("token");
                if (tokenNode != null && !tokenNode.isNull() && tokenNode.isTextual()) {
                    String token = tokenNode.asText();
                    accumulated.append(token);
                    tokenConsumer.accept(token);
                }
            });
            var result = streamResult(accumulated, metadataHolder[0], true);
            onComplete.accept(result);
            return result;
        } catch (Exception e) {
            log.warn("chatStream failed for sessionId={}: {}", sessionId, e.getMessage());
            onError.accept(e);
            return streamResult(accumulated, metadataHolder[0], false);
        }
    }

    private static AgentBackendClient.ChatResult streamResult(StringBuilder accumulated,
                                                               AgentBackendClient.ChatResult metadata,
                                                               boolean succeeded) {
        return new AgentBackendClient.ChatResult(accumulated.toString(),
            metadata == null ? null : metadata.modelUsed(),
            metadata == null ? null : metadata.contextTokens(),
            metadata == null ? null : metadata.contextLength(),
            succeeded && metadata != null && metadata.streamFinalized(),
            succeeded && metadata != null && metadata.memoryUpdated(),
            metadata == null ? null : metadata.backendSessionId(),
            metadata == null ? null : metadata.lastReasoning(),
            succeeded);
    }


    /** Backward-compatible overload without runtime flags. */
    public AgentBackendClient.ChatResult chatStream(String message,
                                                     String sessionId,
                                                     Consumer<String> tokenConsumer,
                                                     Consumer<String> toolCallConsumer,
                                                     java.util.function.BiConsumer<String, String> toolResultConsumer,
                                                     Consumer<AgentBackendClient.ChatResult> onComplete,
                                                     Consumer<Throwable> onError) {
        return chatStream(message, sessionId, null, tokenConsumer, toolCallConsumer,
            toolResultConsumer, msg -> {}, null, null, onComplete, onError);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private AgentBackendClient.ChatResult extractMetadata(JsonNode event) {
        String modelUsed = event.has("modelUsed") ? event.get("modelUsed").asText(null) : null;
        Integer contextTokens = event.has("contextTokens") ? event.get("contextTokens").asInt(0) : null;
        Integer contextLength = event.has("contextLength") ? event.get("contextLength").asInt(0) : null;
        boolean memoryUpdated = event.has("memoryUpdated") && event.get("memoryUpdated").asBoolean(false);
        boolean streamFinalized = event.has("streamFinalized") && event.get("streamFinalized").asBoolean(false);
        java.util.UUID backendSessionId = null;
        JsonNode sessionIdNode = event.get("sessionId");
        if (sessionIdNode != null && !sessionIdNode.isNull() && sessionIdNode.isTextual()) {
            try {
                backendSessionId = java.util.UUID.fromString(sessionIdNode.asText());
            } catch (IllegalArgumentException e) {
                log.warn("Stream metadata contained invalid sessionId: {}", sessionIdNode.asText());
            }
        }
        String lastReasoning = event.has("lastReasoning") && !event.get("lastReasoning").isNull()
            ? event.get("lastReasoning").asText(null) : null;
        return new AgentBackendClient.ChatResult(null, modelUsed, contextTokens, contextLength,
            streamFinalized, memoryUpdated, backendSessionId, lastReasoning);
    }

    /**
     * Build a chat request body, including runtime flags and routing IDs when a
     * Telegram bot session is provided.
     */
    private Map<String, Object> buildChatBody(String message, String sessionId, BotSessionEntity runtime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("sessionId", sessionId);
        }
        if (runtime == null) {
            return body;
        }
        // Forward user identity to the backend so the system prompt can include
        // the real user name, language code, and platform-specific context.
        if (runtime.getUserId() != null && !runtime.getUserId().isBlank()) {
            body.put("userId", runtime.getUserId());
        }
        if (runtime.getUsername() != null && !runtime.getUsername().isBlank()) {
            body.put("username", runtime.getUsername());
        }
        // firstName and languageCode are stored as metadata by BotMessageProcessor
        String firstName = runtime.getMetadata("firstName");
        if (firstName != null && !firstName.isBlank()) {
            body.put("firstName", firstName);
        }
        String languageCode = runtime.getMetadata("languageCode");
        if (languageCode != null && !languageCode.isBlank()) {
            body.put("languageCode", languageCode);
        }
        // chatType — dm/group/channel — tells the model which chat context it's in
        String chatType = runtime.getMetadata("chatType");
        if (chatType != null && !chatType.isBlank()) {
            body.put("chatType", chatType);
        }
        // Forward Telegram routing IDs to the backend
        if (runtime.getChatId() != null && !runtime.getChatId().isBlank()) {
            body.put("chatId", runtime.getChatId());
        }
        String threadId = runtime.getMetadata("threadId");
        if (threadId != null && !threadId.isBlank()) {
            body.put("threadId", threadId);
        }
        if (runtime.isFastMode()) {
            body.put("fastMode", true);
        }
        // Per-session model override (/model command) — the backend threads it
        // through ModelRequestOptions so every model call in the turn uses it.
        String model = com.azhukov.agent.bot.config.ModelSelection.forRequest(properties, runtime.getModelOverride());
        if (model != null && !model.isBlank()) {
            body.put("model", model);
        }
        if (runtime.getReasoningLevel() != null && !runtime.getReasoningLevel().isBlank()) {
            body.put("reasoningEffort", runtime.getReasoningLevel());
        }
        if (runtime.isVoiceMode()) {
            body.put("voiceMode", true);
        }
        if (runtime.getMetadata("personality") != null) {
            body.put("personality", runtime.getMetadata("personality"));
        }
        if (runtime.getMetadata("subgoal") != null) {
            body.put("subgoal", runtime.getMetadata("subgoal"));
        }
        // /yolo — per-session approval bypass (Hermes parity: it must reach the
        // backend turn, not just flip a local flag).
        if (runtime.isYoloMode()) {
            body.put("yoloMode", true);
            body.put("sessionYolo", true); // backend persists as session metadata fallback
        }
        // /verbose — richer tool-call rendering in the assistant's replies.
        if (runtime.isVerboseMode()) {
            body.put("verboseMode", true);
        }
        // /footer — per-session runtime footer toggle.
        if (runtime.getMetadata("footerEnabled") != null) {
            body.put("footerEnabled", Boolean.parseBoolean(runtime.getMetadata("footerEnabled")));
        }
        return body;
    }

    // ------------------------------------------------------------------
    // TTS & transcription
    // ------------------------------------------------------------------

    /**
     * Synthesize text to speech via the backend TTS endpoint.
     */
    public byte[] tts(String text, String voice) {
        Map<String, Object> body = body();
        body.put("text", text);
        if (voice != null && !voice.isBlank()) {
            body.put("voice", voice);
        }
        try {
            byte[] audio = restClient.post()
                .uri("/api/v1/agent/tts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(byte[].class);
            return audio != null ? audio : new byte[0];
        } catch (Exception e) {
            log.warn("tts failed: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * Transcribe audio via the backend transcription endpoint.
     */
    public String transcribe(byte[] audioBytes) {
        try {
            org.springframework.http.client.MultipartBodyBuilder builder = new org.springframework.http.client.MultipartBodyBuilder();
            builder.part("file", new org.springframework.core.io.ByteArrayResource(audioBytes) {
                @Override public String getFilename() { return "audio.ogg"; }
            }, org.springframework.http.MediaType.parseMediaType("audio/ogg"));
            String json = restClient.post()
                .uri("/api/v1/agent/transcribe")
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return null;
            JsonNode node = objectMapper.readTree(json);
            return node.path("text").asText(null);
        } catch (Exception e) {
            log.warn("transcribe failed: {}", e.getMessage());
            return null;
        }
    }
}