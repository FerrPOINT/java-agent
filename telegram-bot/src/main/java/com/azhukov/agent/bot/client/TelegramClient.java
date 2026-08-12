package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Typed wrapper around the Telegram Bot API.
 * Supports sendMessage, editMessageText, deleteMessage, sendChatAction,
 * sendPhoto, sendDocument, sendVoice, getFile, answerCallbackQuery, setMyCommands.
 *
 * <p><b>Error classification:</b> {@link #callApi} and {@link #callMultipartApi}
 * throw {@link TelegramApiException} on non-{@code ok} Telegram API responses,
 * carrying the error code, description, and (for 429) {@code retry_after}.
 * The high-level convenience wrappers (e.g. {@link #sendMessage}, {@link #deleteMessage})
 * catch non-429 exceptions internally and collapse them to {@code Optional.empty()}
 * or {@code false} to preserve their existing return contract.
 * {@code 429} exceptions propagate from {@link #editMessageText} so that
 * {@code StreamEditor} can apply adaptive rate-limit logic.
 */
@Slf4j
public class TelegramClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String botToken;
    private final Semaphore rateLimiter;
    private final int rateLimitPerSecond;
    private final ScheduledExecutorService rateLimitScheduler;
    private boolean linkPreviewEnabled = true; // B3.7: default to enabling link previews

    // B3: Track whether the last API call returned HTTP 409 (conflict)
    private volatile boolean lastCallConflict = false;

    // Track the last API call's error code (e.g. 400, 429) so callers can
    // distinguish failure reasons (message-too-long vs flood vs other).
    // Cleared at the start of each callApi invocation. 0 means "no error / success".
    private volatile int lastApiErrorCode = 0;

    public TelegramClient(RestClient restClient, ObjectMapper objectMapper, String botToken, int rateLimitPerSecond) {
        this(restClient, objectMapper, botToken, rateLimitPerSecond, true);
    }

    public TelegramClient(RestClient restClient, ObjectMapper objectMapper, String botToken, int rateLimitPerSecond, boolean linkPreviewEnabled) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.botToken = botToken != null ? botToken : "";
        this.rateLimitPerSecond = rateLimitPerSecond;
        this.linkPreviewEnabled = linkPreviewEnabled;
        this.rateLimiter = rateLimitPerSecond > 0
                ? new Semaphore(rateLimitPerSecond, true)
                : null;
        this.rateLimitScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "tg-rate-limiter");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Acquire a rate-limit permit. When rate limiting is enabled the permit
     * is released in the {@code finally} block of the API call (via
     * {@link #releaseRateLimit}) rather than on a fixed timer, so the
     * release happens after the API call completes — keeping the
     * effective rate within the configured limit.
     */
    private void acquireRateLimit() {
        if (rateLimiter != null) {
            try {
                if (!rateLimiter.tryAcquire(5, TimeUnit.SECONDS)) {
                    log.warn("Rate limit exceeded, proceeding anyway");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rate limit acquire interrupted, proceeding anyway");
            }
        }
    }

    /**
     * Release the rate-limit permit acquired by {@link #acquireRateLimit}.
     * Called in the {@code finally} block of each API call so the permit
     * is returned after the call completes (not on a fixed 1s timer).
     */
    private void releaseRateLimit() {
        if (rateLimiter != null) {
            rateLimiter.release();
        }
    }

    /**
     * B3: Returns true if the last callApi returned HTTP 409 (conflict —
     * another polling instance is active). The flag is cleared at the start
     * of each callApi invocation.
     *
     * @return true if the last API call was a 409 conflict
     */
    public boolean isLastCallConflict() {
        return lastCallConflict;
    }

    /**
     * Returns the error code from the last callApi invocation.
     * 0 means success (no error). Non-zero values are Telegram API error codes
     * (e.g. 400 for bad request / message too long, 429 for rate limit).
     * Cleared at the start of each callApi invocation.
     *
     * @return the last API error code, or 0 if the last call succeeded
     */
    public int getLastApiErrorCode() {
        return lastApiErrorCode;
    }

    /** Mask token for logging — show first 4 and last 4 chars only. */
    static String maskToken(String token) {
        if (token == null || token.length() <= 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    // ─── Text messages ────────────────────────────────────────────

    /**
     * Send a plain text message. Equivalent to
     * {@code sendMessage(chatId, text, null, null, null, false)}.
     */
    public Optional<Long> sendMessage(long chatId, String text) {
        return sendMessage(chatId, text, null, null, null, false);
    }

    /**
     * Send a text message with parse mode, reply-to, inline keyboard and
     * (optional) thread routing. Backward-compatible overload.
     */
    public Optional<Long> sendMessage(long chatId, String text, String parseMode,
                                       Long replyToMessageId, String replyMarkup) {
        return sendMessage(chatId, text, parseMode, replyToMessageId, null, replyMarkup, false);
    }

    /**
     * Send a text message with optional thread routing and silent delivery.
     *
     * <p>429 (rate limit) handling: when Telegram responds with 429 and a
     * {@code retry_after} parameter, the call blocks for that many seconds
     * and retries once. This is the only high-level method that performs a
     * blocking retry on 429 — {@link #editMessageText} propagates the 429
     * exception to the caller (e.g. {@code StreamEditor}) for non-blocking
     * handling.
     *
     * @param chatId              target chat id
     * @param text                message text
     * @param parseMode           parse mode (MarkdownV2, HTML, or null)
     * @param replyToMessageId    optional reply-to message id
     * @param messageThreadId     optional message_thread_id (forum topic)
     * @param disableNotification when true, delivers silently (no push)
     * @return the sent message id wrapped in Optional, or empty on failure
     */
    public Optional<Long> sendMessage(long chatId, String text, String parseMode,
                                       Long replyToMessageId, Integer messageThreadId,
                                       boolean disableNotification) {
        return sendMessage(chatId, text, parseMode, replyToMessageId, messageThreadId, null, disableNotification);
    }

    /**
     * Full-featured sendMessage with reply markup, thread routing and
     * notification control. All other overloads delegate here.
     */
    public Optional<Long> sendMessage(long chatId, String text, String parseMode,
                                       Long replyToMessageId, Integer messageThreadId,
                                       String replyMarkup, boolean disableNotification) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("text", text);
        if (parseMode != null && !parseMode.isBlank()) params.put("parse_mode", parseMode);
        if (replyToMessageId != null) params.put("reply_to_message_id", replyToMessageId);
        if (messageThreadId != null) params.put("message_thread_id", messageThreadId);
        if (replyMarkup != null && !replyMarkup.isBlank()) params.put("reply_markup", replyMarkup);
        if (disableNotification) params.put("disable_notification", true);
        // B3.7: Link preview options — disable preview if configured
        if (!linkPreviewEnabled) {
            params.put("disable_web_page_preview", true);
        }
        try {
            Optional<TelegramResponse> response = callApi("sendMessage", params);
            return response.flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
        } catch (TelegramApiException e) {
            if (e.isRateLimit()) {
                // sendMessage keeps the blocking 429 retry — it's important for delivery
                int retryAfter = e.getRetryAfter();
                if (retryAfter >= 0) {
                    log.warn("sendMessage 429 rate limit, blocking {}s before retry", retryAfter);
                    try {
                        Thread.sleep(retryAfter * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Optional.empty();
                    }
                    // Single retry
                    try {
                        Optional<TelegramResponse> retry = callApi("sendMessage", params);
                        return retry.flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
                    } catch (TelegramApiException retryEx) {
                        log.warn("sendMessage 429 retry also failed: {}", retryEx.getMessage());
                        return Optional.empty();
                    }
                }
                log.warn("sendMessage 429 rate limit (no retry_after): {}", e.getMessage());
                return Optional.empty();
            }
            // Non-429 error: check if we should retry without reply_to_message_id
            if (replyToMessageId != null && shouldRetryWithoutReply(e.getErrorDescription())) {
                log.debug("sendMessage failed with reply_to_message_id={}, retrying without", replyToMessageId);
                Map<String, Object> retryParams = new LinkedHashMap<>(params);
                retryParams.remove("reply_to_message_id");
                try {
                    Optional<TelegramResponse> retry = callApi("sendMessage", retryParams);
                    return retry.flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
                } catch (TelegramApiException retryEx) {
                    log.warn("sendMessage retry without reply_to also failed: {}", retryEx.getMessage());
                    return Optional.empty();
                }
            }
            log.warn("sendMessage failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Check whether the error description warrants a retry without
     * {@code reply_to_message_id}. Only "message thread not found" and
     * "reply message not found" errors qualify — other errors (chat not
     * found, blocked by user, etc.) should not trigger a retry.
     */
    private static boolean shouldRetryWithoutReply(String errorDescription) {
        if (errorDescription == null) return false;
        String lower = errorDescription.toLowerCase();
        return lower.contains("message thread not found")
            || lower.contains("reply message not found")
            || lower.contains("reply_to_message_id");
    }

    public boolean editMessageText(long chatId, long messageId, String text, String parseMode) {
        return editMessageText(chatId, messageId, text, parseMode, false);
    }

    /**
     * Edit a message with an optional silent (disable_notification) flag.
     * B7: When disableNotification is true, the edit is delivered silently
     * (no push notification). Used during streaming to avoid notification spam.
     *
     * @param chatId              target chat id
     * @param messageId           message id to edit
     * @param text                 new text
     * @param parseMode           parse mode (MarkdownV2, HTML, or null)
     * @param disableNotification when true, delivers silently (no push)
     * @return true if the edit succeeded
     */
    public boolean editMessageText(long chatId, long messageId, String text, String parseMode, boolean disableNotification) {
        return editMessageText(chatId, messageId, text, parseMode, disableNotification, null);
    }

    /**
     * Edit a message with an optional reply_markup (e.g. to remove inline buttons
     * by passing an empty keyboard JSON or null to clear).
     *
     * @param chatId              target chat id
     * @param messageId           message id to edit
     * @param text                 new text
     * @param parseMode           parse mode (MarkdownV2, HTML, or null)
     * @param disableNotification when true, delivers silently (no push)
     * @param replyMarkup         inline keyboard JSON or null to remove buttons
     * @return true if the edit succeeded
     */
    public boolean editMessageText(long chatId, long messageId, String text, String parseMode,
                                   boolean disableNotification, String replyMarkup) {
        return editMessageText(chatId, messageId, text, parseMode, disableNotification, replyMarkup, null);
    }

    /**
     * Edit a message with optional thread routing (message_thread_id).
     *
     * @param messageThreadId optional message_thread_id (forum topic)
     * @return true if the edit succeeded
     */
    public boolean editMessageText(long chatId, long messageId, String text, String parseMode,
                                   boolean disableNotification, String replyMarkup,
                                   Integer messageThreadId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("message_id", messageId);
        params.put("text", text);
        if (parseMode != null && !parseMode.isBlank()) params.put("parse_mode", parseMode);
        if (disableNotification) params.put("disable_notification", true);
        if (replyMarkup != null) {
            params.put("reply_markup", replyMarkup);
        }
        if (messageThreadId != null) params.put("message_thread_id", messageThreadId);
        try {
            return callApi("editMessageText", params).isPresent();
        } catch (TelegramApiException e) {
            if (e.isRateLimit()) {
                // Let 429 propagate so StreamEditor can apply adaptive backoff
                throw e;
            }
            log.warn("editMessageText failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean deleteMessage(long chatId, long messageId) {
        Map<String, Object> params = Map.of("chat_id", chatId, "message_id", messageId);
        try {
            return callApi("deleteMessage", params).isPresent();
        } catch (TelegramApiException e) {
            log.warn("deleteMessage failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Edit the reply markup (inline keyboard) of a message.
     * Pass {@code null} for replyMarkup to remove the inline keyboard entirely.
     *
     * @param chatId       target chat id
     * @param messageId    message id to edit
     * @param replyMarkup  inline keyboard JSON, or null to remove buttons
     * @return true if the edit succeeded
     */
    public boolean editMessageReplyMarkup(long chatId, long messageId, String replyMarkup) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("message_id", messageId);
        if (replyMarkup != null) {
            params.put("reply_markup", replyMarkup);
        } else {
            // Empty inline keyboard to remove buttons
            params.put("reply_markup", Map.of("inline_keyboard", List.of()));
        }
        try {
            return callApi("editMessageReplyMarkup", params).isPresent();
        } catch (TelegramApiException e) {
            log.warn("editMessageReplyMarkup failed: {}", e.getMessage());
            return false;
        }
    }

    // ─── Chat actions ─────────────────────────────────────────────

    public boolean sendChatAction(long chatId, String action) {
        Map<String, Object> params = Map.of("chat_id", chatId, "action", action);
        try {
            return callApi("sendChatAction", params).isPresent();
        } catch (TelegramApiException e) {
            log.warn("sendChatAction failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean sendTyping(long chatId) {
        return sendChatAction(chatId, "typing");
    }

    // ─── Message reactions ─────────────────────────────────────────

    /**
     * Set a message reaction emoji, or clear all reactions when {@code emoji}
     * is empty or blank.
     *
     * @param emoji the reaction emoji, or empty string to clear reactions
     */
    public boolean setMessageReaction(long chatId, long messageId, String emoji) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("message_id", messageId);
        if (emoji == null || emoji.isEmpty()) {
            // Clear reactions: send an empty array
            params.put("reaction", List.of());
        } else {
            params.put("reaction", List.of(Map.of("type", "emoji", "emoji", emoji)));
        }
        try {
            return callApi("setMessageReaction", params).isPresent();
        } catch (TelegramApiException e) {
            log.warn("setMessageReaction failed: {}", e.getMessage());
            return false;
        }
    }

    // ─── Media ────────────────────────────────────────────────────

    public Optional<Long> sendPhoto(long chatId, byte[] photo, String caption, String parseMode) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));
        builder.part("photo", new ByteArrayResource(photo) {
            @Override public String getFilename() { return "photo.jpg"; }
        }, MediaType.IMAGE_JPEG);
        if (caption != null && !caption.isBlank()) builder.part("caption", caption);
        if (parseMode != null && !parseMode.isBlank()) builder.part("parse_mode", parseMode);
        try {
            return callMultipartApi("sendPhoto", builder.build()).flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
        } catch (TelegramApiException e) {
            log.warn("sendPhoto failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Long> sendDocument(long chatId, byte[] document, String fileName,
                                        String caption, String parseMode) {
        String name = (fileName == null || fileName.isBlank()) ? "document" : fileName;
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));
        builder.part("document", new ByteArrayResource(document) {
            @Override public String getFilename() { return name; }
        }, MediaType.APPLICATION_OCTET_STREAM);
        if (caption != null && !caption.isBlank()) builder.part("caption", caption);
        if (parseMode != null && !parseMode.isBlank()) builder.part("parse_mode", parseMode);
        try {
            return callMultipartApi("sendDocument", builder.build()).flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
        } catch (TelegramApiException e) {
            log.warn("sendDocument failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Long> sendVoice(long chatId, byte[] voice, String caption) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));
        builder.part("voice", new ByteArrayResource(voice) {
            @Override public String getFilename() { return "voice.ogg"; }
        }, MediaType.parseMediaType("audio/ogg"));
        if (caption != null && !caption.isBlank()) builder.part("caption", caption);
        try {
            return callMultipartApi("sendVoice", builder.build()).flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
        } catch (TelegramApiException e) {
            log.warn("sendVoice failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── File download ────────────────────────────────────────────

    public Optional<Map<String, Object>> getFile(String fileId) {
        Map<String, Object> params = Map.of("file_id", fileId);
        try {
            return callApi("getFile", params).map(TelegramResponse::resultAsMap);
        } catch (TelegramApiException e) {
            log.warn("getFile failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<byte[]> downloadFile(String filePath) {
        try {
            byte[] data = restClient.get()
                .uri("https://api.telegram.org/file/bot{token}/{path}", botToken, filePath)
                .retrieve()
                .body(byte[].class);
            return Optional.ofNullable(data);
        } catch (Exception e) {
            log.warn("downloadFile failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Bot info ─────────────────────────────────────────────────

    /**
     * Call the Telegram {@code getMe} API to fetch information about the bot,
     * including its username.
     *
     * @return the bot information as a map (keys include "id", "username", "first_name"), or empty on failure
     */
    public Optional<Map<String, Object>> getMe() {
        try {
            return callApi("getMe", Map.of()).map(TelegramResponse::resultAsMap);
        } catch (TelegramApiException e) {
            log.warn("getMe failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Callback queries ─────────────────────────────────────────

    public boolean answerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("callback_query_id", callbackQueryId);
        if (text != null && !text.isBlank()) params.put("text", text);
        params.put("show_alert", showAlert);
        try {
            return callApi("answerCallbackQuery", params).isPresent();
        } catch (TelegramApiException e) {
            log.warn("answerCallbackQuery failed: {}", e.getMessage());
            return false;
        }
    }

    // ─── Commands registration ────────────────────────────────────

    public boolean setMyCommands(List<Map<String, String>> commands) {
        try {
            Map<String, Object> params = Map.of("commands", commands);
            return callApi("setMyCommands", params).isPresent();
        } catch (Exception e) {
            log.warn("setMyCommands failed: {}", e.getMessage());
            return false;
        }
    }

    // B2.7: Forum commands — register commands scoped to a specific chat (for forum topics)
    public boolean setMyCommandsForChat(long chatId, List<Map<String, String>> commands) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("commands", commands);
            params.put("scope", Map.of("type", "chat", "chat_id", chatId));
            return callApi("setMyCommands", params).isPresent();
        } catch (Exception e) {
            log.warn("setMyCommandsForChat failed: {}", e.getMessage());
            return false;
        }
    }

    // ─── Webhook management ───────────────────────────────────────

    public boolean setWebhook(String url, String secretToken) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("url", url != null ? url : "");
        if (secretToken != null && !secretToken.isBlank()) {
            params.put("secret_token", secretToken);
        }
        try {
            return callApi("setWebhook", params).isPresent();
        } catch (TelegramApiException e) {
            log.warn("setWebhook failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean deleteWebhook() {
        try {
            return callApi("deleteWebhook", Map.of()).isPresent();
        } catch (TelegramApiException e) {
            log.warn("deleteWebhook failed: {}", e.getMessage());
            return false;
        }
    }

    public Optional<Map<String, Object>> getWebhookInfo() {
        try {
            return callApi("getWebhookInfo", Map.of()).map(TelegramResponse::resultAsMap);
        } catch (TelegramApiException e) {
            log.warn("getWebhookInfo failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── getUpdates (long polling) ────────────────────────────────

    @SuppressWarnings("unchecked")
    public Optional<List<Map<String, Object>>> getUpdates(long offset, int limit, int timeoutSeconds) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("offset", offset);
        params.put("limit", limit);
        params.put("timeout", timeoutSeconds);
        try {
            return callApi("getUpdates", params).map(resp -> {
                Object result = resp.result();
                if (result instanceof List<?> list) {
                    return (List<Map<String, Object>>) list;
                }
                return List.<Map<String, Object>>of();
            });
        } catch (TelegramApiException e) {
            // 409 conflict is already recorded via lastCallConflict
            log.warn("getUpdates failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Internal ─────────────────────────────────────────────────

    /**
     * Low-level Bot API call (JSON body). Throws {@link TelegramApiException}
     * on non-{@code ok} Telegram responses so callers can inspect the error
     * code and description. Returns {@link Optional#empty()} (without
     * throwing) only when the bot token is blank or the HTTP body is null
     * (e.g. network-level null response).
     *
     * <p>The {@link #isLastCallConflict()} and {@link #getLastApiErrorCode()}
     * side channels are still updated for backward compatibility, but new
     * code should prefer catching {@link TelegramApiException}.
     *
     * @param method Telegram Bot API method name
     * @param params request body parameters
     * @return the successful {@link TelegramResponse}, or empty when the
     *         token is blank or the response body is null
     * @throws TelegramApiException on non-{@code ok} API responses
     */
    public Optional<TelegramResponse> callApi(String method, Map<String, Object> params) {
        if (botToken.isBlank()) {
            log.warn("Bot token is empty; cannot call {}", method);
            return Optional.empty();
        }
        lastCallConflict = false; // B3: clear at start of each call
        lastApiErrorCode = 0; // Clear error code at start of each call
        acquireRateLimit();
        try {
            log.debug("Telegram API call: POST /bot{}/{}, token length={}", maskToken(botToken), method, botToken.length());
            TelegramResponse response = restClient.post()
                .uri("/bot{token}/{method}", botToken, method)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .body(params)
                .retrieve()
                .body(TelegramResponse.class);
            if (response == null) {
                log.warn("Telegram {} returned null response body", method);
                return Optional.empty();
            }
            if (!response.isSuccess()) {
                int code = response.errorCode() != null ? response.errorCode() : 0;
                String desc = response.description();
                lastApiErrorCode = code;
                // B3: Detect HTTP 409 conflict (another polling instance)
                if (code == 409) {
                    lastCallConflict = true;
                    log.warn("Telegram {} returned 409 Conflict: {}", method, desc);
                }
                // Determine retry_after for 429 responses
                int retryAfter = -1;
                if (code == 429 && response.parameters() != null
                    && response.parameters().containsKey("retry_after")) {
                    retryAfter = response.parameters().get("retry_after").asInt(1);
                }
                throw new TelegramApiException(code, desc, retryAfter);
            }
            return Optional.of(response);
        } catch (TelegramApiException e) {
            throw e; // Re-throw typed exceptions
        } catch (Exception e) {
            // Check for HTTP 409 conflict from RestClient (comes as HttpStatusCodeException)
            String msg = e.getMessage();
            if (msg != null && msg.contains("409 Conflict")) {
                lastCallConflict = true;
                lastApiErrorCode = 409;
                log.warn("Telegram {} returned 409 Conflict: {}", method, msg);
                throw new TelegramApiException(409, msg, -1);
            }
            log.warn("Telegram {} exception: {}", method, msg);
            lastApiErrorCode = -1; // Indicate exception (not a Telegram API error code)
            throw new TelegramApiException(-1, msg, -1);
        } finally {
            releaseRateLimit();
        }
    }

    /**
     * Low-level Bot API call (multipart body). Throws {@link TelegramApiException}
     * on non-{@code ok} responses, including 429 with {@code retry_after}.
     *
     * <p>429 retry logic: when a 429 response includes {@code retry_after},
     * this method blocks for that many seconds and retries once (same
     * behaviour as {@link #callApi}). Callers that prefer non-blocking
     * 429 handling should catch {@link TelegramApiException} and inspect
     * {@link TelegramApiException#getRetryAfter()}.
     *
     * @param method Telegram Bot API method name
     * @param parts  multipart form parts
     * @return the successful {@link TelegramResponse}, or empty when the
     *         token is blank or the response body is null
     * @throws TelegramApiException on non-{@code ok} API responses
     */
    Optional<TelegramResponse> callMultipartApi(String method, MultiValueMap<String, org.springframework.http.HttpEntity<?>> parts) {
        if (botToken.isBlank()) {
            log.warn("Bot token is empty; cannot call {}", method);
            return Optional.empty();
        }
        acquireRateLimit();
        try {
            TelegramResponse response = restClient.post()
                .uri("/bot{token}/{method}", botToken, method)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(TelegramResponse.class);
            if (response == null) {
                log.warn("Telegram {} (multipart) returned null response", method);
                return Optional.empty();
            }
            if (!response.isSuccess()) {
                int code = response.errorCode() != null ? response.errorCode() : 0;
                String desc = response.description();
                int retryAfter = -1;
                if (code == 429 && response.parameters() != null
                    && response.parameters().containsKey("retry_after")) {
                    retryAfter = response.parameters().get("retry_after").asInt(1);
                }
                throw new TelegramApiException(code, desc, retryAfter);
            }
            return Optional.of(response);
        } catch (TelegramApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Telegram {} (multipart) exception: {}", method, e.getMessage());
            throw new TelegramApiException(-1, e.getMessage(), -1);
        } finally {
            releaseRateLimit();
        }
    }
}