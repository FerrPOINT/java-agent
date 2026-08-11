package com.azhukov.agent.bot.rich;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.client.TelegramResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bot API 10.1 Rich Messages support.
 *
 * <p>Ported from the original project's {@code sendRichMessage} / {@code _bot_supports_rich} logic.
 * Provides:
 * <ul>
 * <li>Capability detection via getMe (checks for rich_message support)</li>
 * <li>{@link #sendRichMessage} — send raw markdown as a rich message</li>
 * <li>Content limit validation (32,768 chars)</li>
 * <li>Automatic fallback to MarkdownV2 on capability errors</li>
 * <li>Latching off after permanent failures</li>
 * </ul>
 */
@Slf4j
public class RichMessageSupport {

 /** Bot API 10.1 rich message character limit. */
 public static final int RICH_MESSAGE_MAX_CHARS = 32768;

 private final TelegramClient telegramClient;
 private volatile boolean richMessagesEnabled = true;
 private volatile boolean richSendDisabled = false;
 private volatile boolean richCapabilityChecked = false;
 private volatile boolean richCapabilityAvailable = false;

 public RichMessageSupport(TelegramClient telegramClient) {
 this.telegramClient = telegramClient;
 }

 /**
 * Check if the bot supports rich messages via getMe capability detection.
 * Called once and cached — if the bot doesn't support rich, we latch off.
 */
 public synchronized boolean botSupportsRich() {
 if (richCapabilityChecked) {
 return richCapabilityAvailable && !richSendDisabled;
 }
 richCapabilityChecked = true;
 try {
 Optional<Map<String, Object>> me = telegramClient.callApi("getMe", Map.of())
 .map(r -> r.resultAsMap());
 if (me.isPresent()) {
 // Bot API 10.1: check if the bot supports rich messages
 // The getMe response may include a "supports_rich_messages" field
 Object supports = me.get().get("supports_rich_messages");
 if (supports != null) {
 richCapabilityAvailable = Boolean.TRUE.equals(supports) || "true".equals(String.valueOf(supports));
 } else {
 // If the field is absent, try sending a test rich message draft
 // For now, assume capability is available — the first send will latch off if not
 richCapabilityAvailable = true;
 }
 }
 } catch (Exception e) {
 log.debug("[rich] getMe capability check failed: {}", e.getMessage());
 richCapabilityAvailable = false;
 }
 return richCapabilityAvailable && !richSendDisabled;
 }

 /**
 * Check whether content fits within the rich message character limit.
 */
 public boolean contentFitsRichLimits(String content) {
 return content != null && content.length() <= RICH_MESSAGE_MAX_CHARS;
 }

 /**
 * Determine if a rich message send should be attempted for the given content.
 */
 public boolean shouldAttemptRich(String content) {
 return richMessagesEnabled
 && !richSendDisabled
 && content != null
 && !content.isBlank()
 && contentFitsRichLimits(content)
 && botSupportsRich();
 }

 /**
 * Send a rich message with raw markdown content.
 *
 * <p>Returns the message ID on success, or empty if:
 * <ul>
 * <li>Rich is not supported / latched off → caller should fall back to MarkdownV2</li>
 * <li>The send failed with a permanent error → rich is latched off and caller falls back</li>
 * <li>A transient error occurred → caller should NOT retry (duplicate risk)</li>
 * </ul>
 *
 * @param chatId target chat id
 * @param content raw markdown content
 * @param replyToId optional reply_to message id
 * @param threadId optional message_thread_id
 * @return Optional with message ID on success, empty to signal fallback
 */
 public Optional<Long> sendRichMessage(long chatId, String content, Long replyToId, Long threadId) {
 if (!shouldAttemptRich(content)) {
 return Optional.empty();
 }

 Map<String, Object> payload = new LinkedHashMap<>();
 payload.put("chat_id", chatId);
 payload.put("rich_message", Map.of("markdown", content));

 if (threadId != null) {
 payload.put("message_thread_id", threadId);
 }
 if (replyToId != null) {
 // Rich messages use reply_parameters, not reply_to_message_id
 payload.put("reply_parameters", Map.of("message_id", replyToId));
 }

 try {
 Optional<TelegramResponse> response = telegramClient.callApi("sendRichMessage", payload);
 if (response.isPresent() && response.get().isSuccess()) {
 return Optional.ofNullable(response.get().resultMessageIdAsLong());
 }
 // API returned an error response — check if it's a capability/permanent error
 String error = response.map(TelegramResponse::errorMessage).orElse("unknown error");
 if (isRichCapabilityError(error)) {
 log.debug("[rich] sendRichMessage capability error ({}), latching off", error);
 richSendDisabled = true;
 }
 // Signal fallback for both permanent and per-message errors
 return Optional.empty();
 } catch (Exception e) {
 String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
 if (isRichCapabilityError(msg)) {
 log.debug("[rich] sendRichMessage capability error ({}), latching off", e.getMessage());
 richSendDisabled = true;
 } else {
 log.debug("[rich] sendRichMessage error: {}", e.getMessage());
 }
 return Optional.empty();
 }
 }

 /**
 * Check if an error indicates the rich endpoint is unavailable (capability error).
 * These latch rich off permanently — retrying is pointless.
 */
 static boolean isRichCapabilityError(String error) {
 if (error == null) return false;
 String lower = error.toLowerCase();
 return lower.contains("not found")
 || lower.contains("does not exist")
 || lower.contains("no such method")
 || lower.contains("endpoint")
 || lower.contains("unsupported")
 || lower.contains("not implemented");
 }

 /**
 * Check if an error indicates a permanent fallback (bad request, etc.).
 */
 static boolean isRichFallbackError(String error) {
 if (error == null) return false;
 String lower = error.toLowerCase();
 return lower.contains("bad request")
 || isRichCapabilityError(error);
 }

 /** Force-disable rich messages (for testing). */
 public void setRichSendDisabled(boolean disabled) {
 this.richSendDisabled = disabled;
 }

 /** Check if rich send is disabled (for testing). */
 public boolean isRichSendDisabled() {
 return richSendDisabled;
 }

 /** Enable/disable rich messages (for testing/config). */
 public void setRichMessagesEnabled(boolean enabled) {
 this.richMessagesEnabled = enabled;
 }

 /** Reset state (for testing). */
 public void reset() {
 richSendDisabled = false;
 richCapabilityChecked = false;
 richCapabilityAvailable = false;
 richMessagesEnabled = true;
 }
}