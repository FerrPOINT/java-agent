package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Per-domain delegate covering messaging endpoints:
 * chat (sync), chat (SSE streaming), TTS synthesis and audio transcription.
 */
@Service
@Slf4j
public class MessageApiClient extends BaseBackendClient {

    private static final long STREAM_IDLE_TIMEOUT_MS = 300_000; // 5 minutes of no data — allows compression/LLM calls
    private static final int MAX_CONNECT_RETRIES = 3;
    private static final long[] CONNECT_BACKOFF_MS = {2_000, 4_000, 8_000};

    public MessageApiClient(@Qualifier("backendRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    // ------------------------------------------------------------------
    // Synchronous chat
    // ------------------------------------------------------------------

    /**
     * Send a chat message to the agent backend, optionally carrying runtime flags
     * from a Telegram bot session (fast mode, reasoning effort, voice mode, etc.).
     */
    public AgentBackendClient.ChatResult chat(String message, String sessionId, BotSessionEntity runtime) {
        Map<String, Object> body = buildChatBody(message, sessionId, runtime);

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
            JsonNode responseField = node.get("response");
            String responseText;
            if (responseField == null || responseField.isNull()) {
                // Try "content" field (the actual ChatResponseDto field name)
                responseField = node.get("content");
            }
            if (responseField == null || responseField.isNull()) {
                log.warn("Backend chat response missing 'response' or 'content' field: {}", responseJson);
                return new AgentBackendClient.ChatResult("Error: missing 'response' field in backend reply");
            }
            responseText = responseField.asText();

            String modelUsed = node.has("modelUsed") ? node.get("modelUsed").asText(null) : null;
            Integer contextTokens = node.has("contextTokens") ? node.get("contextTokens").asInt(0) : null;
            Integer contextLength = node.has("contextLength") ? node.get("contextLength").asInt(0) : null;
            boolean memoryUpdated = node.has("memoryUpdated") && node.get("memoryUpdated").asBoolean(false);
            java.util.UUID backendSessionId = null;
            JsonNode sessionIdNode = node.get("sessionId");
            if (sessionIdNode != null && !sessionIdNode.isNull() && sessionIdNode.isTextual()) {
                try {
                    backendSessionId = java.util.UUID.fromString(sessionIdNode.asText());
                } catch (IllegalArgumentException e) {
                    log.warn("Backend returned invalid sessionId: {}", sessionIdNode.asText());
                }
            }

            return new AgentBackendClient.ChatResult(responseText, modelUsed, contextTokens, contextLength, false, memoryUpdated, backendSessionId);
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
                                                    Consumer<AgentBackendClient.ChatResult> onComplete,
                                                    Consumer<Throwable> onError) {
        Map<String, Object> body = buildChatBody(message, sessionId, runtime);

        StringBuilder accumulated = new StringBuilder();
        AgentBackendClient.ChatResult[] metadataHolder = new AgentBackendClient.ChatResult[1];

        try {
            // --- Initial connection with retry on connection errors ---
            InputStream is = null;
            Exception lastConnectError = null;
            for (int attempt = 0; attempt <= MAX_CONNECT_RETRIES; attempt++) {
                try {
                    is = restClient.post()
                        .uri("/api/v1/agent/chat/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(InputStream.class);
                    if (is != null) {
                        break; // connection established
                    }
                } catch (Exception ce) {
                    lastConnectError = ce;
                    // Only retry on connection errors (IOException, ConnectException),
                    // NOT on HTTP errors (4xx/5xx responses from the backend)
                    if (isConnectionError(ce) && attempt < MAX_CONNECT_RETRIES) {
                        long backoff = CONNECT_BACKOFF_MS[attempt];
                        log.warn("SSE connection attempt {}/{} failed (connection error), retrying in {}ms: {}",
                            attempt + 1, MAX_CONNECT_RETRIES + 1, backoff, ce.getMessage());
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw ie;
                        }
                        continue;
                    }
                    // Not a connection error, or retries exhausted — propagate
                    throw ce;
                }
            }

            if (is == null) {
                onError.accept(new IllegalStateException("Backend stream returned null input stream after retries"));
                return new AgentBackendClient.ChatResult("");
            }

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {

                // Idle-timeout watchdog: if no data arrives for STREAM_IDLE_TIMEOUT_MS,
                // close the reader to unblock readLine() and abort the stream.
                final long[] lastDataTime = {System.currentTimeMillis()};
                ScheduledExecutorService watchdog =
                    Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "sse-idle-watchdog");
                        t.setDaemon(true);
                        return t;
                    });
                final IOException[] timeoutSignal = new IOException[1];
                ScheduledFuture<?> watchdogTask = watchdog.scheduleAtFixedRate(() -> {
                    if (System.currentTimeMillis() - lastDataTime[0] > STREAM_IDLE_TIMEOUT_MS) {
                        timeoutSignal[0] = new IOException("SSE stream idle timeout: no data for "
                            + STREAM_IDLE_TIMEOUT_MS + "ms");
                        try {
                            reader.close(); // unblocks readLine()
                        } catch (IOException e) { log.debug("SSE watchdog reader close exception: {}", e.getMessage()); }
                    }
                }, STREAM_IDLE_TIMEOUT_MS, 10_000, TimeUnit.MILLISECONDS);

                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lastDataTime[0] = System.currentTimeMillis();
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).strip();
                            if (!data.isEmpty()) {
                                try {
                                    JsonNode event = objectMapper.readTree(data);
                                    String type = event.path("type").asText("");
                                    if ("error".equalsIgnoreCase(type)) {
                                        String errorMsg = event.path("error").asText(
                                            event.path("message").asText("Unknown stream error"));
                                        onError.accept(new RuntimeException(errorMsg));
                                        return new AgentBackendClient.ChatResult(accumulated.toString());
                                    }
                                    if ("metadata".equalsIgnoreCase(type)) {
                                        metadataHolder[0] = extractMetadata(event);
                                        continue;
                                    }
                                    // tool_calls event — LLM decided to call tools.
                                    // Do NOT send a progress bubble here; the tool_start event
                                    // (fired per-tool right before execution) will send the bubble.
                                    // Sending here causes duplicate bubbles in Telegram.
                                    if ("tool_calls".equalsIgnoreCase(type)) {
                                        continue;
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
                                        continue;
                                    }
                                    // tool_result event — notify tool result consumer
                                    if ("tool_result".equalsIgnoreCase(type)) {
                                        String toolName = event.path("toolName").asText("");
                                        String toolResult = event.path("toolResult").asText("");
                                        if (!toolName.isEmpty()) {
                                            toolResultConsumer.accept(toolName, toolResult);
                                        }
                                        continue;
                                    }
                                    // retry event — notify retry consumer so the bot can show retry status
                                    if ("retry".equalsIgnoreCase(type)) {
                                        String retryMsg = event.path("error").asText(
                                            event.path("message").asText("Retrying..."));
                                        retryConsumer.accept(retryMsg);
                                        continue;
                                    }
                                    // continuation event — also surface as retry status
                                    if ("continuation".equalsIgnoreCase(type)) {
                                        String contMsg = event.path("error").asText(
                                            event.path("message").asText("Continuing..."));
                                        retryConsumer.accept(contMsg);
                                        continue;
                                    }
                                    // commentary event — the text was already streamed via token events,
                                    // so skip it to avoid duplicating the text in the output.
                                    if ("commentary".equalsIgnoreCase(type)) {
                                        continue;
                                    }
                                    JsonNode tokenNode = event.get("token");
                                    if (tokenNode != null && !tokenNode.isNull() && tokenNode.isTextual()) {
                                        String token = tokenNode.asText();
                                        accumulated.append(token);
                                        tokenConsumer.accept(token);
                                    }
                                    if ("done".equalsIgnoreCase(type)) {
                                        AgentBackendClient.ChatResult result = metadataHolder[0] != null
                                            ? new AgentBackendClient.ChatResult(accumulated.toString(), metadataHolder[0].modelUsed(),
                                            metadataHolder[0].contextTokens(), metadataHolder[0].contextLength(),
                                            metadataHolder[0].streamFinalized(), metadataHolder[0].memoryUpdated(),
                                            metadataHolder[0].backendSessionId())
                                            : new AgentBackendClient.ChatResult(accumulated.toString());
                                        onComplete.accept(result);
                                        return result;
                                    }
                                } catch (Exception parseEx) {
                                    log.warn("Failed to parse SSE data line: {}", data, parseEx);
                                }
                            }
                        }
                    }
                    // If the watchdog closed the reader, throw to signal timeout
                    if (timeoutSignal[0] != null) {
                        throw timeoutSignal[0];
                    }
                } finally {
                    watchdogTask.cancel(false);
                    watchdog.shutdownNow();
                }
                // Stream ended without explicit "done" event
                AgentBackendClient.ChatResult result = metadataHolder[0] != null
                    ? new AgentBackendClient.ChatResult(accumulated.toString(), metadataHolder[0].modelUsed(),
                    metadataHolder[0].contextTokens(), metadataHolder[0].contextLength(), false,
                    metadataHolder[0].memoryUpdated(), metadataHolder[0].backendSessionId())
                    : new AgentBackendClient.ChatResult(accumulated.toString());
                onComplete.accept(result);
                return result;
            }
        } catch (Exception e) {
            log.warn("chatStream failed for sessionId={}: {}", sessionId, e.getMessage());
            onError.accept(e);
            return new AgentBackendClient.ChatResult(accumulated.toString());
        }
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
            toolResultConsumer, msg -> {}, onComplete, onError);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Determine if an exception represents a connection-level failure
     * (as opposed to an HTTP error response from the backend).
     * Only connection errors are retried during initial SSE connection.
     */
    private static boolean isConnectionError(Throwable e) {
        if (e == null) return false;
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.net.ConnectException) return true;
            if (current instanceof java.io.IOException) return true;
            // Spring RestClient wraps connection errors in ResourceAccessException
            String className = current.getClass().getName();
            if (className.contains("ResourceAccessException")) return true;
            if (className.contains("ConnectException")) return true;
            current = current.getCause();
        }
        return false;
    }

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
        return new AgentBackendClient.ChatResult(null, modelUsed, contextTokens, contextLength, streamFinalized, memoryUpdated, backendSessionId);
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