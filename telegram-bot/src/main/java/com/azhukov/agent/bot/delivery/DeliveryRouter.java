package com.azhukov.agent.bot.delivery;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Delivery Router — routes cron job output to appropriate destinations.
 *
 * <p>Ported from the original project's {@code gateway/delivery.py} DeliveryRouter. Supports:
 * <ul>
 * <li>Delivery target parsing: origin, local, telegram:chatId, telegram:chatId:threadId</li>
 * <li>Routing cron job output to specified targets</li>
 * <li>Thread-not-found recovery</li>
 * <li>Long output truncation with full-output file save</li>
 * <li>Local file delivery fallback</li>
 * </ul>
 */
@Slf4j
public class DeliveryRouter {

 public static final int MAX_PLATFORM_OUTPUT = 4000;
 public static final int TRUNCATED_VISIBLE = 3800;

 private final Path outputDir;

 public DeliveryRouter() {
 this(Path.of(System.getProperty("user.home"), ".java-agent", "cron", "output"));
 }

 public DeliveryRouter(Path outputDir) {
 this.outputDir = outputDir;
 }

 /** A single delivery target. */
 public static class DeliveryTarget {
 public enum Platform { TELEGRAM, LOCAL }

 public final Platform platform;
 public final String chatId; // null means use home channel
 public final String threadId;
 public final boolean isOrigin;
 public final boolean isExplicit;

 public DeliveryTarget(Platform platform, String chatId, String threadId, boolean isOrigin, boolean isExplicit) {
 this.platform = platform;
 this.chatId = chatId;
 this.threadId = threadId;
 this.isOrigin = isOrigin;
 this.isExplicit = isExplicit;
 }

 /** Parse a delivery target string. */
 public static DeliveryTarget parse(String target, DeliveryTarget origin) {
 if (target == null || target.isBlank()) {
 return new DeliveryTarget(Platform.LOCAL, null, null, false, false);
 }
 String stripped = target.strip();
 String lower = stripped.toLowerCase();

 if ("origin".equals(lower)) {
 if (origin != null && origin.platform == Platform.TELEGRAM) {
 return new DeliveryTarget(origin.platform, origin.chatId, origin.threadId, true, false);
 }
 return new DeliveryTarget(Platform.LOCAL, null, null, true, false);
 }

 if ("local".equals(lower)) {
 return new DeliveryTarget(Platform.LOCAL, null, null, false, false);
 }

 // Check for platform:chat_id or platform:chat_id:thread_id format
 if (stripped.contains(":")) {
 String[] parts = stripped.split(":", 3);
 String platformStr = parts[0].toLowerCase();
 String chatId = parts.length > 1 ? parts[1] : null;
 String threadId = parts.length > 2 ? parts[2] : null;

 if ("telegram".equals(platformStr)) {
 return new DeliveryTarget(Platform.TELEGRAM, chatId, threadId, false, true);
 }
 // Unknown platform, treat as local
 return new DeliveryTarget(Platform.LOCAL, null, null, false, false);
 }

 // Just a platform name (use home channel)
 if ("telegram".equals(lower)) {
 return new DeliveryTarget(Platform.TELEGRAM, null, null, false, false);
 }
 return new DeliveryTarget(Platform.LOCAL, null, null, false, false);
 }

 /** Convert back to string format. */
 public String to_string() {
 if (isOrigin) return "origin";
 if (platform == Platform.LOCAL) return "local";
 if (chatId != null && threadId != null) return "telegram:" + chatId + ":" + threadId;
 if (chatId != null) return "telegram:" + chatId;
 return "telegram";
 }
 }

 /** Result of a delivery to a single target. */
 public record DeliveryResult(boolean success, String result, String error) {
 public static DeliveryResult ok(String result) {
 return new DeliveryResult(true, result, null);
 }
 public static DeliveryResult fail(String error) {
 return new DeliveryResult(false, null, error);
 }
 }

 /**
 * Deliver content to all specified targets.
 *
 * @param content the message/output to deliver
 * @param targets list of delivery targets
 * @param jobName optional job name (for local file output)
 * @param metadata additional metadata
 * @param sendFunction function to send to Telegram (chatId, threadId, content) → success
 * @return Map of target → DeliveryResult
 */
 public Map<String, DeliveryResult> deliver(
 String content,
 List<DeliveryTarget> targets,
 String jobName,
 Map<String, Object> metadata,
 TelegramSendFunction sendFunction
 ) {
 Map<String, DeliveryResult> results = new LinkedHashMap<>();
 for (DeliveryTarget target : targets) {
 try {
 DeliveryResult result;
 if (target.platform == DeliveryTarget.Platform.LOCAL) {
 result = deliverLocal(content, jobName, metadata);
 } else {
 result = deliverToPlatform(target, content, metadata, sendFunction);
 }
 results.put(target.to_string(), result);
 } catch (Exception e) {
 results.put(target.to_string(), DeliveryResult.fail(e.getMessage()));
 }
 }
 return results;
 }

 /** Save content to local files. */
 public DeliveryResult deliverLocal(String content, String jobName, Map<String, Object> metadata) {
 try {
 String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
 Path outputPath = outputDir.resolve(timestamp + ".md");
 Files.createDirectories(outputPath.getParent());

 StringBuilder sb = new StringBuilder();
 if (jobName != null) {
 sb.append("# ").append(jobName).append("\n\n");
 } else {
 sb.append("# Delivery Output\n\n");
 }
 sb.append("**Timestamp:** ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
 if (metadata != null) {
 metadata.forEach((k, v) -> sb.append("\n**").append(k).append(":** ").append(v));
 }
 sb.append("\n\n---\n\n").append(content);

 Files.writeString(outputPath, sb.toString());
 return DeliveryResult.ok(outputPath.toString());
 } catch (IOException e) {
 return DeliveryResult.fail(e.getMessage());
 }
 }

 /** Deliver content to a messaging platform (Telegram). */
 public DeliveryResult deliverToPlatform(
 DeliveryTarget target,
 String content,
 Map<String, Object> metadata,
 TelegramSendFunction sendFunction
 ) {
 if (target.chatId == null) {
 return DeliveryResult.fail("No chat ID for telegram delivery");
 }

 // Guard: truncate oversized cron output
 if (content.length() > MAX_PLATFORM_OUTPUT) {
 String jobId = metadata != null ? String.valueOf(metadata.getOrDefault("job_id", "unknown")) : "unknown";
 Path savedPath = saveFullOutput(content, jobId);
 log.info("Cron output truncated ({} chars) — full output: {}", content.length(), savedPath);
 content = content.substring(0, TRUNCATED_VISIBLE)
 + "\n\n... [truncated, full output saved to " + savedPath + "]";
 }

 long chatId;
 try {
 chatId = Long.parseLong(target.chatId);
 } catch (NumberFormatException e) {
 return DeliveryResult.fail("Invalid chat ID: " + target.chatId);
 }

 Long threadId = null;
 if (target.threadId != null) {
 try {
 threadId = Long.parseLong(target.threadId);
 } catch (NumberFormatException e) {
 // Thread ID is not numeric — could be a named topic
 // In a full implementation, this would resolve the topic name
 log.debug("Non-numeric thread_id '{}', will attempt as-is", target.threadId);
 }
 }

 boolean success = sendFunction.send(chatId, threadId, content);
 if (!success) {
 // Check for thread-not-found recovery
 if (target.threadId != null) {
 log.info("Delivery to thread {} failed, retrying without thread", target.threadId);
 success = sendFunction.send(chatId, null, content);
 }
 if (!success) {
 return DeliveryResult.fail("Telegram delivery failed");
 }
 }
 return DeliveryResult.ok("delivered");
 }

 /** Save full cron output to disk and return the file path. */
 public Path saveFullOutput(String content, String jobId) {
 try {
 String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
 Files.createDirectories(outputDir);
 Path path = outputDir.resolve(jobId + "_" + timestamp + ".txt");
 Files.writeString(path, content);
 return path;
 } catch (IOException e) {
 log.warn("Failed to save full output: {}", e.getMessage());
 return outputDir.resolve("error.txt");
 }
 }

 /** Functional interface for sending to Telegram. */
 @FunctionalInterface
 public interface TelegramSendFunction {
 boolean send(long chatId, Long threadId, String content);
 }
}