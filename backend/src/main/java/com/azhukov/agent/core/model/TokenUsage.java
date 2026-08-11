package com.azhukov.agent.core.model;

import java.util.Objects;

/**
 * Real token usage from an API response.
 * Extracted from the API response's usage field, not estimated.
 * <p>
 * Mirrors the original project's context_engine.py update_from_response which reads
 * prompt_tokens, completion_tokens, cache_read_tokens, cache_write_tokens,
 * and reasoning_tokens from the normalized usage dict.
 */
public record TokenUsage(
 int promptTokens,
 int completionTokens,
 int totalTokens,
 int cacheReadTokens,
 int cacheWriteTokens,
 int reasoningTokens
) {
 public TokenUsage {
 Objects.requireNonNull(promptTokens);
 Objects.requireNonNull(completionTokens);
 if (totalTokens == 0) {
 totalTokens = promptTokens + completionTokens;
 }
 }

 public static TokenUsage of(int promptTokens, int completionTokens) {
 return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens, 0, 0, 0);
 }

 public static TokenUsage of(int promptTokens, int completionTokens, int cacheRead, int cacheWrite, int reasoning) {
 return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens, cacheRead, cacheWrite, reasoning);
 }

 /**
 * Build from a raw API usage map (OpenAI / Anthropic format).
 * Handles both legacy keys (prompt_tokens, completion_tokens) and
 * canonical keys (input_tokens, output_tokens, cache_read_input_tokens, etc.)
 */
 @SuppressWarnings("unchecked")
 public static TokenUsage fromMap(java.util.Map<String, Object> usage) {
 if (usage == null) {
 return new TokenUsage(0, 0, 0, 0, 0, 0);
 }

 int prompt = getInt(usage, "prompt_tokens", "input_tokens");
 int completion = getInt(usage, "completion_tokens", "output_tokens");
 int total = getInt(usage, "total_tokens");
 if (total == 0) total = prompt + completion;

 int cacheRead = getInt(usage, "cache_read_tokens", "cache_read_input_tokens", "cached_tokens");
 int cacheWrite = getInt(usage, "cache_write_tokens", "cache_creation_input_tokens", "cache_write_tokens");
 int reasoning = getInt(usage, "reasoning_tokens", "reasoning_token_count");

 return new TokenUsage(prompt, completion, total, cacheRead, cacheWrite, reasoning);
 }

 private static int getInt(java.util.Map<String, Object> map, String... keys) {
 for (String key : keys) {
 Object val = map.get(key);
 if (val instanceof Number n) {
 return n.intValue();
 }
 if (val instanceof String s) {
 try {
 return Integer.parseInt(s.trim());
 } catch (NumberFormatException ignored) {
 }
 }
 }
 return 0;
 }
}