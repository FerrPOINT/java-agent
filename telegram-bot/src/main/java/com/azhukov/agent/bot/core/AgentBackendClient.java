package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
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
import jakarta.annotation.PostConstruct;

/**
 * HTTP client for the agent backend.
 * Uses the qualified "backendRestClient" bean configured in {@link com.azhukov.agent.bot.config.BotConfig}.
 *
 * <p>POST to {@code /api/v1/agent/chat} with JSON body
 * {@code {"message":"...","sessionId":"..."}} (sessionId omitted when null).
 * Parses the {@code "response"} field from the returned JSON.
 *
 * <p>GET to {@code /api/v1/agent/health} for health checks.
 */
@Service
@Slf4j
public class AgentBackendClient {

 private static final long STREAM_IDLE_TIMEOUT_MS = 120_000; // 2 minutes of no data
 private static final int MAX_CONNECT_RETRIES = 3;
 private static final long[] CONNECT_BACKOFF_MS = {2_000, 4_000, 8_000};

 private final RestClient restClient;
 private final ObjectMapper objectMapper;
 private final BotProperties properties;
 private String baseUrl;

 public AgentBackendClient(@Qualifier("backendRestClient") RestClient restClient,
 ObjectMapper objectMapper,
 BotProperties properties) {
 this.restClient = restClient;
 this.objectMapper = objectMapper;
 this.properties = properties;
 }

 @PostConstruct
 void init() {
 baseUrl = properties.getBackendUrl();
 }
 /**
 * Result of a chat call, including the response content and runtime metadata
 * for the footer (model, context usage, working directory).
 */
 public record ChatResult(
 String content,
 String modelUsed,
 Integer contextTokens,
 Integer contextLength,
 boolean streamFinalized,
 boolean memoryUpdated,
 java.util.UUID backendSessionId
 ) {
 public ChatResult(String content) {
     this(content, null, null, null, false, false, null);
 }

 public ChatResult(String content, String modelUsed, Integer contextTokens, Integer contextLength) {
     this(content, modelUsed, contextTokens, contextLength, false, false, null);
 }

 public ChatResult(String content, String modelUsed, Integer contextTokens, Integer contextLength, boolean streamFinalized) {
     this(content, modelUsed, contextTokens, contextLength, streamFinalized, false, null);
 }

 public ChatResult(String content, String modelUsed, Integer contextTokens, Integer contextLength, boolean streamFinalized, boolean memoryUpdated) {
     this(content, modelUsed, contextTokens, contextLength, streamFinalized, memoryUpdated, null);
 }
 }

 /**
 * Send a chat message to the agent backend, optionally carrying runtime flags
 * from a Telegram bot session (fast mode, reasoning effort, voice mode, etc.).
 *
 * @param message the user's message text
 * @param sessionId the session UUID (may be null — omitted from body when null)
 * @param runtime optional runtime state to forward to the backend
 * @return the response content and metadata from the agent, or an error message on failure
 */
 public ChatResult chat(String message, String sessionId, com.azhukov.agent.bot.session.BotSessionEntity runtime) {
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
 return new ChatResult("Error: empty response from backend");
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
 return new ChatResult("Error: missing 'response' field in backend reply");
 }
 responseText = responseField.asText();

 // Memory updates are silent — not injected into response text 

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

 return new ChatResult(responseText, modelUsed, contextTokens, contextLength, false, memoryUpdated, backendSessionId);
 } catch (Exception e) {
 log.warn("Backend chat failed for sessionId={}: {}", sessionId, e.getMessage());
 return new ChatResult("Error: " + e.getMessage());
 }
 }

 /**
 * Backward-compatible overload without runtime flags.
 */
 public ChatResult chat(String message, String sessionId) {
 return chat(message, sessionId, null);
 }

 /**
 * Check backend health.
 *
 * @return true if the backend is healthy, false otherwise
 */
 public boolean health() {
 try {
 String responseJson = restClient.get()
 .uri("/api/v1/agent/health")
 .retrieve()
 .body(String.class);

 if (responseJson == null || responseJson.isBlank()) {
 return false;
 }

 JsonNode node = objectMapper.readTree(responseJson);
 JsonNode status = node.get("status");
 if (status != null) {
 String statusText = status.asText();
 return "UP".equalsIgnoreCase(statusText) || "OK".equalsIgnoreCase(statusText);
 }
 // If no status field, assume healthy if we got a response
 return true;
 } catch (Exception e) {
 log.warn("Backend health check failed: {}", e.getMessage());
 return false;
 }
 }

 // ------------------------------------------------------------------
 // Session management
 // ------------------------------------------------------------------

 /**
 * Reset (clear) the conversation session on the backend.
 *
 * @param sessionId the session UUID
 * @return {@code true} if the reset succeeded, {@code false} on error
 */
 public boolean resetSession(String sessionId) {
 try {
 restClient.post()
 .uri("/api/v1/agent/session/{sessionId}/reset", sessionId)
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("resetSession failed for sessionId={}: {}", sessionId, e.getMessage());
 return false;
 }
 }

 /**
 * Get the full conversation context for a session.
 *
 * @param sessionId the session UUID
 * @return the context as a {@link JsonNode}, or {@code null} on error
 */
 public JsonNode getContext(String sessionId) {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/session/{sessionId}/context", sessionId)
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) {
 return null;
 }
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("getContext failed for sessionId={}: {}", sessionId, e.getMessage());
 return null;
 }
 }

 /**
 * Get token/cost usage statistics for a session.
 *
 * @param sessionId the session UUID
 * @return the usage as a {@link JsonNode}, or {@code null} on error
 */
 public JsonNode getUsage(String sessionId) {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/session/{sessionId}/usage", sessionId)
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) {
 return null;
 }
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("getUsage failed for sessionId={}: {}", sessionId, e.getMessage());
 return null;
 }
 }

 /**
 * List all sessions belonging to a user.
 *
 * @param userId the user identifier
 * @return an array of session summaries, or an empty array on error
 */
 public JsonNode listSessionsByUser(String userId) {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/sessions/{userId}", userId)
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) {
 return objectMapper.createArrayNode();
 }
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("listSessionsByUser failed for userId={}: {}", userId, e.getMessage());
 return objectMapper.createArrayNode();
 }
 }

 // ------------------------------------------------------------------
 // Memory & skills
 // ------------------------------------------------------------------

 /**
 * Get the list of memory entries from the backend.
 *
 * @return a {@link JsonNode} (array of strings), or an empty array on error
 */
 public JsonNode getMemory() {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/memory")
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) {
 return objectMapper.createArrayNode();
 }
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("getMemory failed: {}", e.getMessage());
 return objectMapper.createArrayNode();
 }
 }

 /**
 * Get the list of available skills from the backend.
 *
 * @return a {@link JsonNode} (array of strings), or an empty array on error
 */
 public JsonNode getSkills() {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/skills")
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) {
 return objectMapper.createArrayNode();
 }
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("getSkills failed: {}", e.getMessage());
 return objectMapper.createArrayNode();
 }
 }

 // ------------------------------------------------------------------
 // Streaming chat
 // ------------------------------------------------------------------

 /**
 * Send a chat message to the agent backend and stream the response back
 * via Server-Sent Events, optionally carrying runtime flags from a Telegram bot session.
 *
 * <p>This is a <strong>blocking</strong> call — the caller should run it
 * in a separate thread.
 *
 * @param message the user's message text
 * @param sessionId the session UUID (may be null)
 * @param runtime optional runtime state to forward to the backend
 * @param tokenConsumer called for each token received
 * @param toolCallConsumer called for each tool call announcement (tool name)
 * @param toolResultConsumer called for each tool result (toolName, resultPreview)
 * @param retryConsumer called for each retry/continuation status update (message text)
 * @param onComplete called when the stream completes successfully with the final metadata
 * @param onError called with an exception if the stream fails
 * @return the accumulated response content and metadata (also delivered via onComplete callback)
 */
 public ChatResult chatStream(String message,
 String sessionId,
 com.azhukov.agent.bot.session.BotSessionEntity runtime,
 Consumer<String> tokenConsumer,
 Consumer<String> toolCallConsumer,
 java.util.function.BiConsumer<String, String> toolResultConsumer,
 Consumer<String> retryConsumer,
 Consumer<ChatResult> onComplete,
 Consumer<Throwable> onError) {
 Map<String, Object> body = buildChatBody(message, sessionId, runtime);

 StringBuilder accumulated = new StringBuilder();
 ChatResult[] metadataHolder = new ChatResult[1];

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
 		return new ChatResult("");
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
 return new ChatResult(accumulated.toString());
 }
 if ("metadata".equalsIgnoreCase(type)) {
 metadataHolder[0] = extractMetadata(event);
 continue;
 }
 // tool_calls event — notify tool call consumer
 if ("tool_calls".equalsIgnoreCase(type)) {
 JsonNode toolCallsNode = event.get("toolCalls");
 if (toolCallsNode != null && toolCallsNode.isArray()) {
 for (JsonNode tc : toolCallsNode) {
 String toolName = tc.path("name").asText("unknown");
 toolCallConsumer.accept(toolName);
 }
 }
 continue;
 }
 // tool_start event — notify tool call consumer
 if ("tool_start".equalsIgnoreCase(type)) {
 String toolName = event.path("toolName").asText("");
 if (!toolName.isEmpty()) {
 toolCallConsumer.accept(toolName);
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
 JsonNode tokenNode = event.get("token");
 if (tokenNode != null && !tokenNode.isNull() && tokenNode.isTextual()) {
 String token = tokenNode.asText();
 accumulated.append(token);
 tokenConsumer.accept(token);
 }
 if ("done".equalsIgnoreCase(type)) {
 ChatResult result = metadataHolder[0] != null
 ? new ChatResult(accumulated.toString(), metadataHolder[0].modelUsed(),
 metadataHolder[0].contextTokens(), metadataHolder[0].contextLength(),
 metadataHolder[0].streamFinalized(), metadataHolder[0].memoryUpdated(),
 metadataHolder[0].backendSessionId())
 : new ChatResult(accumulated.toString());
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
 ChatResult result = metadataHolder[0] != null
 ? new ChatResult(accumulated.toString(), metadataHolder[0].modelUsed(),
 metadataHolder[0].contextTokens(), metadataHolder[0].contextLength(), false,
 metadataHolder[0].memoryUpdated(), metadataHolder[0].backendSessionId())
 : new ChatResult(accumulated.toString());
 onComplete.accept(result);
 return result;
 }
 } catch (Exception e) {
 log.warn("chatStream failed for sessionId={}: {}", sessionId, e.getMessage());
 onError.accept(e);
 return new ChatResult(accumulated.toString());
 }
 }

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

 private ChatResult extractMetadata(JsonNode event) {
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
 return new ChatResult(null, modelUsed, contextTokens, contextLength, streamFinalized, memoryUpdated, backendSessionId);
 }

 // ------------------------------------------------------------------
 // Compress, undo, approve, deny, agents, insights
 // ------------------------------------------------------------------

 public String compressSession(String sessionId, String focus) {
 Map<String, Object> body = new LinkedHashMap<>();
 if (focus != null && !focus.isBlank()) {
 body.put("focus", focus);
 }
 try {
 restClient.post()
 .uri("/api/v1/agent/session/{sessionId}/compress", sessionId)
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .toBodilessEntity();
 return "Context compressed.";
 } catch (Exception e) {
 log.warn("compressSession failed for sessionId={}: {}", sessionId, e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String undoTurns(String sessionId, int turns) {
 try {
 Integer deleted = restClient.post()
 .uri(uriBuilder -> uriBuilder.path("/api/v1/agent/session/{sessionId}/undo")
 .queryParam("turns", turns)
 .build(sessionId))
 .retrieve()
 .body(Integer.class);
 return "Undid " + (deleted != null ? deleted : 0) + " messages.";
 } catch (Exception e) {
 log.warn("undoTurns failed for sessionId={}: {}", sessionId, e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String compressSessionPartial(String sessionId, int keepLastN) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("keepLastN", keepLastN);
 try {
 restClient.post()
 .uri("/api/v1/agent/session/{sessionId}/compress", sessionId)
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .toBodilessEntity();
 return "Context compressed (kept last " + keepLastN + " exchanges).";
 } catch (Exception e) {
 log.warn("compressSessionPartial failed for sessionId={}: {}", sessionId, e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String listCheckpoints() {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/checkpoint")
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) return "No checkpoints found.";
 JsonNode array = objectMapper.readTree(json);
 if (!array.isArray() || array.isEmpty()) return "No checkpoints found.";
 StringBuilder sb = new StringBuilder("Checkpoints:\n");
 for (JsonNode node : array) {
 String id = node.path("id").asText();
 String desc = node.path("description").asText();
 int files = node.path("fileCount").asInt();
 sb.append(String.format("- %s | %s | %d files\n", id, desc, files));
 }
 return sb.toString().trim();
 } catch (Exception e) {
 log.warn("listCheckpoints failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String restoreCheckpoint(String checkpointId) {
 try {
 restClient.post()
 .uri("/api/v1/agent/checkpoint/{id}/restore", checkpointId)
 .retrieve()
 .toBodilessEntity();
 return "Checkpoint restored: " + checkpointId;
 } catch (Exception e) {
 log.warn("restoreCheckpoint failed for id={}: {}", checkpointId, e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String createCheckpoint(String description) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("description", description);
 try {
 restClient.post()
 .uri("/api/v1/agent/checkpoint")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .toBodilessEntity();
 return "Checkpoint created: " + description;
 } catch (Exception e) {
 log.warn("createCheckpoint failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String approve(boolean all, String scope) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("all", all);
 if (scope != null) body.put("scope", scope);
 try {
 return restClient.post()
 .uri("/api/v1/agent/approve")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .body(String.class);
 } catch (Exception e) {
 log.warn("approve failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String deny(boolean all) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("all", all);
 try {
 return restClient.post()
 .uri("/api/v1/agent/deny")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .body(String.class);
 } catch (Exception e) {
 log.warn("deny failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 /**
 * Resolve an exec-approval callback from an inline button press.
 * <p>
 * Maps the button choice to the backend's approve/deny API:
 * <ul>
 * <li>{@code once} → approve single (scope=sessionKey)</li>
 * <li>{@code session} → approve with scope "session"</li>
 * <li>{@code always} → approve with scope "always"</li>
 * <li>{@code deny} → deny single</li>
 * </ul>
 *
 * @param sessionKey the session key to resolve (from ApprovalStateStore)
 * @param choice the button choice: once, session, always, deny
 * @return the backend response string
 */
 public String resolveApproval(String sessionKey, String choice) {
 if (sessionKey == null || sessionKey.isBlank()) {
 return "No session key";
 }
 try {
 return switch (choice) {
 case "once" -> {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("all", false);
 body.put("scope", sessionKey);
 yield restClient.post()
 .uri("/api/v1/agent/approve")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .body(String.class);
 }
 case "session" -> approve(false, "session");
 case "always" -> approve(false, "always");
 case "deny" -> deny(false);
 default -> "Unknown choice: " + choice;
 };
 } catch (Exception e) {
 log.warn("resolveApproval failed for sessionKey={}, choice={}: {}", sessionKey, choice, e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 /**
 * Resolve a slash-confirm callback from an inline button press.
 * <p>
 * Maps the button choice to the backend's approve/deny API:
 * <ul>
 * <li>{@code once} → approve single (scope=sessionKey)</li>
 * <li>{@code always} → approve with scope "always"</li>
 * <li>{@code cancel} → deny single</li>
 * </ul>
 *
 * @param sessionKey the session key to resolve (from ApprovalStateStore)
 * @param confirmId the confirm prompt ID (unused by backend but logged)
 * @param choice the button choice: once, always, cancel
 * @return the backend response string
 */
 public String resolveSlashConfirm(String sessionKey, String confirmId, String choice) {
 if (sessionKey == null || sessionKey.isBlank()) {
 return "No session key";
 }
 log.debug("Resolving slash-confirm: sessionKey={}, confirmId={}, choice={}", sessionKey, confirmId, choice);
 try {
 return switch (choice) {
 case "once" -> {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("all", false);
 body.put("scope", sessionKey);
 yield restClient.post()
 .uri("/api/v1/agent/approve")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .body(String.class);
 }
 case "always" -> approve(false, "always");
 case "cancel" -> deny(false);
 default -> "Unknown choice: " + choice;
 };
 } catch (Exception e) {
 log.warn("resolveSlashConfirm failed for sessionKey={}, confirmId={}, choice={}: {}",
 sessionKey, confirmId, choice, e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public JsonNode listActiveAgents() {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/agents")
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) return objectMapper.createArrayNode();
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("listActiveAgents failed: {}", e.getMessage());
 return objectMapper.createArrayNode();
 }
 }

 public JsonNode getInsights() {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/insights")
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) return objectMapper.createObjectNode();
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("getInsights failed: {}", e.getMessage());
 return objectMapper.createObjectNode();
 }
 }

 // ------------------------------------------------------------------
 // Phase 2: restart, reload, bundles, branch, background
 // ------------------------------------------------------------------

 public String restart() {
 try {
 restClient.post()
 .uri("/api/v1/agent/restart")
 .retrieve()
 .toBodilessEntity();
 return "Agent restarting...";
 } catch (Exception e) {
 log.warn("restart failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String reloadMcp() {
 try {
 restClient.post()
 .uri("/api/v1/agent/reload-mcp")
 .retrieve()
 .toBodilessEntity();
 return "MCP servers reloaded.";
 } catch (Exception e) {
 log.warn("reloadMcp failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String reloadAll() {
 try {
 restClient.post()
 .uri("/api/v1/agent/reload")
 .retrieve()
 .toBodilessEntity();
 return "Skills and MCP servers reloaded.";
 } catch (Exception e) {
 log.warn("reloadAll failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 // ------------------------------------------------------------------
 // Kanban CRUD
 // ------------------------------------------------------------------

 public JsonNode getKanban() {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/kanban")
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) return objectMapper.createArrayNode();
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("getKanban failed: {}", e.getMessage());
 return objectMapper.createArrayNode();
 }
 }

 public JsonNode addKanbanTask(String text) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("text", text);
 try {
 String json = restClient.post()
 .uri("/api/v1/agent/kanban/add")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) return null;
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("addKanbanTask failed: {}", e.getMessage());
 return null;
 }
 }

 public boolean doneKanbanTask(String id) {
 try {
 restClient.post()
 .uri("/api/v1/agent/kanban/done/{id}", id)
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("doneKanbanTask failed for id={}: {}", id, e.getMessage());
 return false;
 }
 }

 public boolean clearKanban() {
 try {
 restClient.delete()
 .uri("/api/v1/agent/kanban")
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("clearKanban failed: {}", e.getMessage());
 return false;
 }
 }

 public String reloadSkills() {
 try {
 restClient.post()
 .uri("/api/v1/agent/reload-skills")
 .retrieve()
 .toBodilessEntity();
 return "Skills reloaded.";
 } catch (Exception e) {
 log.warn("reloadSkills failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public JsonNode listBundles() {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/bundles")
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) return objectMapper.createArrayNode();
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("listBundles failed: {}", e.getMessage());
 return objectMapper.createArrayNode();
 }
 }

 public String installBundle(String bundleName) {
 try {
 return restClient.post()
 .uri("/api/v1/agent/bundles/install")
 .body(java.util.Map.of("bundleName", bundleName))
 .retrieve()
 .body(String.class);
 } catch (Exception e) {
 log.warn("installBundle failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String uninstallBundle(String bundleName) {
 try {
 return restClient.post()
 .uri("/api/v1/agent/bundles/uninstall")
 .body(java.util.Map.of("bundleName", bundleName))
 .retrieve()
 .body(String.class);
 } catch (Exception e) {
 log.warn("uninstallBundle failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String branchSession(String sessionId, String name) {
 try {
 String url = "/api/v1/agent/session/" + sessionId + "/branch";
 if (name != null && !name.isBlank()) {
 url += "?name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
 }
 String json = restClient.post()
 .uri(url)
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) return "Branch created.";
 JsonNode node = objectMapper.readTree(json);
 JsonNode idNode = node.get("id");
 return idNode != null ? "Branched session: " + idNode.asText() : "Branch created.";
 } catch (Exception e) {
 log.warn("branchSession failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 public String runBackground(String prompt, String sessionId) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("prompt", prompt);
 if (sessionId != null && !sessionId.isBlank()) {
 body.put("sessionId", sessionId);
 }
 try {
 String result = restClient.post()
 .uri("/api/v1/agent/background")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .body(String.class);
 return result != null ? result : "Background task started.";
 } catch (Exception e) {
 log.warn("runBackground failed: {}", e.getMessage());
 return "Error: " + e.getMessage();
 }
 }

 // ------------------------------------------------------------------
 // Memory management (Stage 7.2)
 // ------------------------------------------------------------------

 /**
 * Inject a steer note into the active turn for the given session.
 * The note is appended to the next tool result's content.
 *
 * @param sessionId the session UUID
 * @param text the steer text
 * @return true if accepted by the backend
 */
 public boolean steer(String sessionId, String text) {
 if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
 return false;
 }
 try {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("sessionId", sessionId);
 body.put("text", text);
 String result = restClient.post()
 .uri("/api/v1/agent/steer")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .body(String.class);
 return result != null && result.contains("\"accepted\":true");
 } catch (Exception e) {
 log.warn("steer failed for sessionId={}: {}", sessionId, e.getMessage());
 return false;
 }
 }

 /**
 * List all cron jobs from the backend.
 */
 public JsonNode listCronJobs() {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/cron")
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) {
 return objectMapper.createArrayNode();
 }
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("listCronJobs failed: {}", e.getMessage());
 return objectMapper.createArrayNode();
 }
 }

 /**
 * Delete (dismiss) a cron job by ID.
 */
 public boolean deleteCronJob(String id) {
 try {
 restClient.delete()
 .uri("/api/v1/agent/cron/{id}", id)
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("deleteCronJob failed for id={}: {}", id, e.getMessage());
 return false;
 }
 }

 /**
 * Pause a cron job by ID.
 */
 public boolean pauseCronJob(String id) {
 try {
 restClient.post()
 .uri("/api/v1/agent/cron/{id}/pause", id)
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("pauseCronJob failed for id={}: {}", id, e.getMessage());
 return false;
 }
 }

 /**
 * Resume a cron job by ID.
 */
 public boolean resumeCronJob(String id) {
 try {
 restClient.post()
 .uri("/api/v1/agent/cron/{id}/resume", id)
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("resumeCronJob failed for id={}: {}", id, e.getMessage());
 return false;
 }
 }

 public JsonNode listPendingMemory(String userId) {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/memory/pending/{userId}", userId)
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) return objectMapper.createArrayNode();
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("listPendingMemory failed: {}", e.getMessage());
 return objectMapper.createArrayNode();
 }
 }

 public boolean approvePendingMemory(String userId, String id) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("userId", userId);
 body.put("id", id);
 try {
 Boolean result = restClient.post()
 .uri("/api/v1/agent/memory/approve")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .body(Boolean.class);
 return Boolean.TRUE.equals(result);
 } catch (Exception e) {
 log.warn("approvePendingMemory failed: {}", e.getMessage());
 return false;
 }
 }

 public boolean rejectPendingMemory(String userId, String id) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("userId", userId);
 body.put("id", id);
 try {
 Boolean result = restClient.post()
 .uri("/api/v1/agent/memory/reject")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .body(Boolean.class);
 return Boolean.TRUE.equals(result);
 } catch (Exception e) {
 log.warn("rejectPendingMemory failed: {}", e.getMessage());
 return false;
 }
 }

 public void setMemoryApproval(boolean enabled) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("enabled", enabled);
 try {
 restClient.post()
 .uri("/api/v1/agent/memory/approval")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .toBodilessEntity();
 } catch (Exception e) {
 log.warn("setMemoryApproval failed: {}", e.getMessage());
 }
 }

 public boolean isMemoryApprovalEnabled() {
 try {
 String responseJson = restClient.get()
 .uri("/api/v1/agent/memory/approval")
 .retrieve()
 .body(String.class);
 if (responseJson == null || responseJson.isBlank()) {
 return false;
 }
 return Boolean.parseBoolean(responseJson.trim());
 } catch (Exception e) {
 log.warn("isMemoryApprovalEnabled failed: {}", e.getMessage());
 return false;
 }
 }

 public JsonNode listAllMemory(String userId) {
 try {
 String json = restClient.get()
 .uri("/api/v1/agent/memory/all/{userId}", userId)
 .retrieve()
 .body(String.class);
 if (json == null || json.isBlank()) return objectMapper.createArrayNode();
 return objectMapper.readTree(json);
 } catch (Exception e) {
 log.warn("listAllMemory failed: {}", e.getMessage());
 return objectMapper.createArrayNode();
 }
 }

 public boolean deleteMemory(String userId, String entryId) {
 try {
 restClient.delete()
 .uri("/api/v1/agent/memory/{userId}/{entryId}", userId, entryId)
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("deleteMemory failed: {}", e.getMessage());
 return false;
 }
 }

 public boolean storeMemory(String userId, String text) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("userId", userId != null ? userId : "default");
 body.put("fact", text);
 body.put("category", "user");
 body.put("target", "memory");
 try {
 restClient.post()
 .uri("/api/v1/agent/memory")
 .contentType(MediaType.APPLICATION_JSON)
 .body(body)
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("storeMemory failed: {}", e.getMessage());
 return false;
 }
 }

 /**
 * Synthesize text to speech via the backend TTS endpoint.
 *
 * @param text the text to synthesize
 * @param voice the voice to use (may be null)
 * @return the audio bytes, or empty array on error
 */
 public byte[] tts(String text, String voice) {
 Map<String, Object> body = new LinkedHashMap<>();
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
 *
 * @param audioBytes the audio file bytes
 * @return the transcribed text, or null on error
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

 public boolean clearGoal(String sessionId) {
 if (sessionId == null || sessionId.isBlank()) return false;
 try {
 restClient.post()
 .uri("/api/v1/agent/goal/clear")
 .contentType(MediaType.APPLICATION_JSON)
 .body(java.util.Map.of("sessionId", sessionId))
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("clearGoal failed for sessionId={}: {}", sessionId, e.getMessage());
 return false;
 }
 }

 public boolean setGoal(String sessionId, String goal) {
 if (sessionId == null || sessionId.isBlank()) return false;
 try {
 restClient.post()
 .uri("/api/v1/agent/goal")
 .contentType(MediaType.APPLICATION_JSON)
 .body(java.util.Map.of("sessionId", sessionId, "goal", goal))
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("setGoal failed for sessionId={}: {}", sessionId, e.getMessage());
 return false;
 }
 }

 public boolean pauseGoal(String sessionId) {
 if (sessionId == null || sessionId.isBlank()) return false;
 try {
 restClient.post()
 .uri("/api/v1/agent/goal/pause")
 .contentType(MediaType.APPLICATION_JSON)
 .body(java.util.Map.of("sessionId", sessionId))
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("pauseGoal failed for sessionId={}: {}", sessionId, e.getMessage());
 return false;
 }
 }

 public boolean resumeGoal(String sessionId) {
 if (sessionId == null || sessionId.isBlank()) return false;
 try {
 restClient.post()
 .uri("/api/v1/agent/goal/resume")
 .contentType(MediaType.APPLICATION_JSON)
 .body(java.util.Map.of("sessionId", sessionId))
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("resumeGoal failed for sessionId={}: {}", sessionId, e.getMessage());
 return false;
 }
 }

 public boolean appendSubgoal(String sessionId, String subgoal) {
 if (sessionId == null || sessionId.isBlank()) return false;
 try {
 restClient.post()
 .uri("/api/v1/agent/subgoal")
 .contentType(MediaType.APPLICATION_JSON)
 .body(java.util.Map.of("sessionId", sessionId, "subgoal", subgoal, "append", "true"))
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("appendSubgoal failed for sessionId={}: {}", sessionId, e.getMessage());
 return false;
 }
 }

 public boolean clearSubgoals(String sessionId) {
 if (sessionId == null || sessionId.isBlank()) return false;
 try {
 restClient.post()
 .uri("/api/v1/agent/subgoal/clear")
 .contentType(MediaType.APPLICATION_JSON)
 .body(java.util.Map.of("sessionId", sessionId))
 .retrieve()
 .toBodilessEntity();
 return true;
 } catch (Exception e) {
 log.warn("clearSubgoals failed for sessionId={}: {}", sessionId, e.getMessage());
 return false;
 }
 }

 /**
 * Expose the base URL (for diagnostics/logging).
 *
 * @return the backend base URL
 */
 public String getBaseUrl() {
 return baseUrl;
 }

 /**
 * Build a chat request body, including runtime flags and routing IDs when a
 * Telegram bot session is provided.
 * <p>
 * When the session carries a {@code chatId} (Telegram chat ID) or a
 * {@code threadId} (forum topic / message thread ID) in its metadata, these
 * are forwarded to the backend so it can use them for routing and context.
 */
 private Map<String, Object> buildChatBody(String message, String sessionId,
 com.azhukov.agent.bot.session.BotSessionEntity runtime) {
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("message", message);
 if (sessionId != null && !sessionId.isBlank()) {
 body.put("sessionId", sessionId);
 }
 if (runtime == null) {
 return body;
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

 /**
 * Backward-compatible overload of {@link #chatStream(String, String, com.azhukov.agent.bot.session.BotSessionEntity,
 * Consumer, Consumer, BiConsumer, Consumer, Consumer, Consumer)} without runtime flags.
 */
 public ChatResult chatStream(String message,
 String sessionId,
 Consumer<String> tokenConsumer,
 Consumer<String> toolCallConsumer,
 java.util.function.BiConsumer<String, String> toolResultConsumer,
 Consumer<ChatResult> onComplete,
 Consumer<Throwable> onError) {
 return chatStream(message, sessionId, null, tokenConsumer, toolCallConsumer,
 toolResultConsumer, msg -> {}, onComplete, onError);
 }
}