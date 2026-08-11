package com.azhukov.agent.core.metadata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Provides model metadata: context length detection from model name,
 * provider prefix stripping, and per-model token estimation.
 * <p>
 * Mirrors the original project's agent/model_metadata.py — detects context length from
 * model name patterns, strips provider prefixes, and caches metadata.
 */
@Slf4j
@Component
public class ModelMetadataService {

 // Provider prefixes that can appear before a model ID (stripped for metadata lookup)
 private static final java.util.Set<String> PROVIDER_PREFIXES = java.util.Set.of(
 "openrouter", "nous", "openai-codex", "copilot", "copilot-acp",
 "gemini", "ollama-cloud", "zai", "kimi-coding", "kimi-coding-cn",
 "stepfun", "minimax", "minimax-oauth", "minimax-cn", "anthropic",
 "deepseek", "custom", "local",
 // Common aliases
 "google", "google-gemini", "google-ai-studio",
 "glm", "z-ai", "z.ai", "zhipu", "github", "github-copilot",
 "github-models", "kimi", "moonshot", "kimi-cn", "moonshot-cn",
 "claude", "deep-seek", "ollama", "opencode-zen", "opencode-go",
 "kilocode", "alibaba", "novita", "qwen-oauth", "xiaomi",
 "arcee", "gmi", "tencent-tokenhub", "nvidia", "nim",
 "nvidia-nim", "nemotron", "qwen-portal", "novita-ai", "novitaai",
 "arcee-ai", "arceeai", "gmi-cloud", "gmicloud",
 "xai", "x-ai", "x.ai", "grok",
 "mimo", "xiaomi-mimo"
 );

 // Ollama-style tags that should NOT be stripped (model:tag format)
 private static final Pattern OLLAMA_TAG_PATTERN = Pattern.compile(
 "^(\\d+\\.?\\d*b|latest|stable|q\\d|fp?\\d|instruct|chat|coder|vision|text)",
 Pattern.CASE_INSENSITIVE
 );

 // Default context length when no detection method succeeds
 public static final int DEFAULT_FALLBACK_CONTEXT = 256_000;

 // Minimum context length required to run the agent
 public static final int MINIMUM_CONTEXT_LENGTH = 64_000;

 // Descending tiers for context length probing
 public static final int[] CONTEXT_PROBE_TIERS = {
 256_000, 128_000, 64_000, 32_000, 16_000, 8_000
 };

 // Hardcoded fallback context lengths — broad model family patterns
 // Keys use substring matching (longest-first), so specific entries before catch-alls
 private static final Map<String, Integer> CONTEXT_LENGTHS = Map.ofEntries(
 // Anthropic Claude
 Map.entry("claude-opus-4-8", 1_000_000),
 Map.entry("claude-opus-4.8", 1_000_000),
 Map.entry("claude-opus-4-7", 1_000_000),
 Map.entry("claude-opus-4.7", 1_000_000),
 Map.entry("claude-opus-4-6", 1_000_000),
 Map.entry("claude-sonnet-4-6", 1_000_000),
 Map.entry("claude-opus-4.6", 1_000_000),
 Map.entry("claude-sonnet-4.6", 1_000_000),
 Map.entry("claude", 200_000),
 // OpenAI GPT
 Map.entry("gpt-5.5", 1_050_000),
 Map.entry("gpt-5.4-nano", 400_000),
 Map.entry("gpt-5.4-mini", 400_000),
 Map.entry("gpt-5.4", 1_050_000),
 Map.entry("gpt-5.1-chat", 128_000),
 Map.entry("gpt-5", 400_000),
 Map.entry("gpt-4.1", 1_047_576),
 Map.entry("gpt-4", 128_000),
 // Google
 Map.entry("gemini", 1_048_576),
 Map.entry("gemma-4", 256_000),
 Map.entry("gemma4", 256_000),
 Map.entry("gemma-3", 131_072),
 Map.entry("gemma", 8_192),
 // DeepSeek
 Map.entry("deepseek-v4-pro", 1_000_000),
 Map.entry("deepseek-v4-flash", 1_000_000),
 Map.entry("deepseek-chat", 1_000_000),
 Map.entry("deepseek-reasoner", 1_000_000),
 Map.entry("deepseek", 128_000),
 // Meta
 Map.entry("llama", 131_072),
 // Qwen
 Map.entry("qwen3.6-plus", 1_048_576),
 Map.entry("qwen3-coder-plus", 1_000_000),
 Map.entry("qwen3-coder", 262_144),
 Map.entry("qwen", 131_072),
 // MiniMax
 Map.entry("minimax-m3", 1_000_000),
 Map.entry("minimax", 204_800),
 // GLM
 Map.entry("glm", 202_752),
 // xAI Grok
 Map.entry("grok-4-fast", 2_000_000),
 Map.entry("grok-4.20", 2_000_000),
 Map.entry("grok-4.3", 1_000_000),
 Map.entry("grok-4", 256_000),
 Map.entry("grok-3", 131_072),
 Map.entry("grok-2", 131_072),
 Map.entry("grok", 131_072),
 // Kimi
 Map.entry("kimi", 262_144),
 // Nemotron
 Map.entry("nemotron", 131_072),
 // Tencent
 Map.entry("hy3-preview", 262_144),
 // Arcee
 Map.entry("trinity", 262_144)
 );

 // Cache for model metadata
 private final Map<String, ModelMetadata> metadataCache = new ConcurrentHashMap<>();

 /**
 * Strip a recognized provider prefix from a model string.
 * "local:my-model" → "my-model"
 * "qwen3.5:27b" → "qwen3.5:27b" (unchanged — Ollama model:tag)
 */
 public String stripProviderPrefix(String model) {
 if (model == null || model.isEmpty()) return model;
 if (!model.contains(":") || model.startsWith("http")) {
 return model;
 }
 String[] parts = model.split(":", 2);
 String prefix = parts[0].trim().toLowerCase();
 String suffix = parts[1];
 if (PROVIDER_PREFIXES.contains(prefix)) {
 // Don't strip if suffix looks like an Ollama tag
 if (OLLAMA_TAG_PATTERN.matcher(suffix.trim()).matches()) {
 return model;
 }
 return suffix;
 }
 return model;
 }

 /**
 * Detect context length from model name.
 * Uses substring matching (longest-first) against known model patterns.
 */
 public int detectContextLength(String model) {
 if (model == null || model.isBlank()) {
 return DEFAULT_FALLBACK_CONTEXT;
 }
 return metadataCache.computeIfAbsent(model, m -> {
 String bare = stripProviderPrefix(m).toLowerCase();
 int contextLength = detectContextLengthFromName(bare);
 int tokensPerChar = estimateCharsPerToken(bare);
 return new ModelMetadata(m, bare, contextLength, tokensPerChar);
 }).contextLength();
 }

 private int detectContextLengthFromName(String modelLower) {
 // Sort keys by length descending for longest-substring matching
 int bestLength = -1;
 int bestContext = -1;
 for (var entry : CONTEXT_LENGTHS.entrySet()) {
 String key = entry.getKey().toLowerCase();
 if (modelLower.contains(key) && key.length() > bestLength) {
 bestLength = key.length();
 bestContext = entry.getValue();
 }
 }
 if (bestContext > 0) {
 return bestContext;
 }
 return DEFAULT_FALLBACK_CONTEXT;
 }

 /**
 * Estimate the number of tokens in a text string for the given model.
 * Default is chars / 4, but some models (CJK-heavy) use chars / 3.
 */
 public int estimateTokens(String text, String model) {
 if (text == null || text.isEmpty()) return 0;
 int modelContext = detectContextLength(model);
 int charsPerToken = 4;
 return text.length() / charsPerToken + 1;
 }

 /**
 * Estimate chars-per-token ratio for a model.
 * CJK-heavy models (GLM, Qwen, Kimi) typically have 3 chars/token.
 */
 private int estimateCharsPerToken(String modelLower) {
 if (modelLower.contains("glm") || modelLower.contains("qwen") || modelLower.contains("kimi")) {
 return 3;
 }
 return 4;
 }

 /**
 * Get full model metadata for a model, caching the result.
 */
 public ModelMetadata getMetadata(String model) {
 if (model == null || model.isBlank()) {
 return new ModelMetadata(model, "", DEFAULT_FALLBACK_CONTEXT, 4);
 }
 return metadataCache.computeIfAbsent(model, m -> {
 String bare = stripProviderPrefix(m).toLowerCase();
 int contextLength = detectContextLengthFromName(bare);
 int charsPerToken = estimateCharsPerToken(bare);
 return new ModelMetadata(m, bare, contextLength, charsPerToken);
 });
 }

 /**
 * Immutable model metadata record.
 */
 public record ModelMetadata(
 String originalName,
 String strippedName,
 int contextLength,
 int charsPerToken
 ) {
 public int estimateTokens(String text) {
 if (text == null || text.isEmpty()) return 0;
 return text.length() / charsPerToken + 1;
 }
 }

 /**
 * Get the compression threshold for a model (fraction of context window).
 * Default is 0.75. Some models may have overrides.
 */
 public double getCompressionThreshold(String model) {
 return 0.75; // default
 }

 /**
 * Clear the metadata cache.
 */
 public void clearCache() {
 metadataCache.clear();
 }
}