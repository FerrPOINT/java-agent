package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Focused helper class for pure static utility methods extracted from
 * {@link TurnExecutor}.
 * <p>
 * These methods have no dependency on instance state and are shared by
 * {@code TurnExecutor}, {@code FallbackModelCaller}, {@code DefaultAgentRuntime},
 * and {@code AgentStreamingService}. Keeping them here avoids bloating
 * {@code TurnExecutor} with unrelated static helpers while preserving the
 * public API (each method is {@code public static} and {@code TurnExecutor}
 * still delegates here for backward compatibility).
 *
 * <h2>Method groups</h2>
 * <ul>
 *   <li><b>Refusal detection:</b> {@link #detectRefusalPattern(String)}</li>
 *   <li><b>Token estimation:</b> {@link #estimateResponseTokens(ChatResponse)},
 *       {@link #estimateResponseTokens(String, List)}</li>
 *   <li><b>Grammar sanitization:</b>
 *       {@link #stripGrammarPatternsFromTools(List)},
 *       {@link #stripPatternAndFormat(Map)}</li>
 *   <li><b>Thinking blocks:</b> {@link #containsThinkingBlocks(List)}</li>
 *   <li><b>Image content:</b> {@link #containsImageContent(List)},
 *       {@link #stripImageContent(List)}</li>
 *   <li><b>Multimodal tool content:</b>
 *       {@link #containsMultimodalToolContent(List)},
 *       {@link #stripMultimodalToolContent(List)}</li>
 *   <li><b>Retry helpers:</b> {@link #extractRetryAfterMs(Exception)},
 *       {@link #lowerMessageContains(Exception, String)}</li>
 *   <li><b>Compression-failure classification:</b>
 *       {@link #classifyForLog(String)}, {@link #isTransient(String)}</li>
 *   <li><b>Backoff sleep:</b> {@link #interruptibleSleep(long)}</li>
 * </ul>
 */
@Slf4j
public final class TurnExecutorUtils {

    /** Body-embedded cooldown hint, e.g. LiteLLM "Try again in 600 seconds". */
    private static final java.util.regex.Pattern TRY_AGAIN_SECONDS =
        java.util.regex.Pattern.compile("try again in\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(?:s\\b|sec\\b|second|seconds)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private TurnExecutorUtils() {
        // Utility class — no instances
    }

    // ──────────────────────────────────────────────────────────────────
    //  Interruptible sleep
    // ──────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────
    //  Pre-API context injection (shared by sync + streaming loops)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Hermes parity (conversation_loop.py:2104-2153): pre-API-call /steer drain.
     * If a steer arrived during the previous API call, inject it into the last
     * tool message NOW so the model sees it on THIS iteration. Without this,
     * steers sent during an API call only land after the NEXT tool batch,
     * which may never come if the model returns a final response.
     * <p>
     * c2: single owner of the steer-marker sanitization + injection scan —
     * previously two verbatim copies in DefaultAgentRuntime and
     * AgentStreamingService.
     *
     * @param context    the mutable context list about to be sent to the model
     * @param steerText  the drained steer text (may be null — no-op)
     * @param sessionId  session id for logging
     * @return true if the steer was injected into a tool message; false when
     *         there was no tool message to inject into (the CALLER must then
     *         put the steer back via {@code steerBuffer.steer(...)} for the
     *         post-batch drain)
     */
    public static boolean injectPreApiSteer(List<Message> context, String steerText, java.util.UUID sessionId) {
        if (steerText == null || context == null) {
            return false;
        }
        String sanitizedSteer = steerText
            .replace(DefaultPromptBuilder.STEER_MARKER_OPEN, "")
            .replace(DefaultPromptBuilder.STEER_MARKER_CLOSE, "");
        String steerMarker = DefaultPromptBuilder.STEER_MARKER_OPEN + "\n"
            + sanitizedSteer + "\n" + DefaultPromptBuilder.STEER_MARKER_CLOSE;
        for (int si = context.size() - 1; si >= 0; si--) {
            Message sm = context.get(si);
            if (sm.toolCallId() != null || sm.role() == com.azhukov.agent.core.model.Role.TOOL) {
                String enhanced = (sm.content() != null ? sm.content() : "") + "\n\n" + steerMarker;
                context.set(si, Message.toolResult(sm.toolCallId(), enhanced, sm.turnIndex()));
                log.info("Pre-API steer drain: injected into tool msg at index {} (session {})", si, sessionId);
                return true;
            }
        }
        return false;
    }

    /**
     * Hermes parity (conversation_loop.py:2154-2172): wall-clock run-budget
     * wrap-up notice. At 80% of runBudgetSeconds, inject a one-shot "wrap up
     * and deliver" notice into the newest tool result. Dormant when
     * runBudgetSeconds is 0 or unset.
     * <p>
     * c2: single owner — previously two verbatim copies (sync "(sync)" log tag
     * and streaming). The log line no longer carries a path tag.
     *
     * @param context                mutable context about to be sent to the model
     * @param runBudgetSeconds       configured run budget (0 = disabled)
     * @param turnStartMillis        wall-clock start of the turn
     * @param wrapupAlreadyInjected  latch holder (int[] of size 1, 0=pending,
     *                               1=injected) — pass the same array across
     *                               loop iterations
     * @return true if the notice was injected this call
     */
    public static boolean maybeInjectRunBudgetWrapup(List<Message> context,
                                                     int runBudgetSeconds,
                                                     long turnStartMillis,
                                                     int[] wrapupAlreadyInjected) {
        if (runBudgetSeconds <= 0 || context == null || wrapupAlreadyInjected == null
            || wrapupAlreadyInjected.length == 0 || wrapupAlreadyInjected[0] != 0) {
            return false;
        }
        long elapsed = (System.currentTimeMillis() - turnStartMillis) / 1000;
        if (elapsed < 0.8 * runBudgetSeconds) {
            return false;
        }
        for (int si = context.size() - 1; si >= 0; si--) {
            Message sm = context.get(si);
            if (sm.toolCallId() != null || sm.role() == com.azhukov.agent.core.model.Role.TOOL) {
                String enhanced = (sm.content() != null ? sm.content() : "")
                    + "\n\n" + DefaultPromptBuilder.RUN_BUDGET_WRAPUP_NOTICE;
                context.set(si, Message.toolResult(sm.toolCallId(), enhanced, sm.turnIndex()));
                wrapupAlreadyInjected[0] = 1;
                log.info("Run budget wrap-up notice injected (budget={}s, elapsed={}s)",
                    runBudgetSeconds, elapsed);
                return true;
            }
        }
        return false;
    }

    /**
     * c2: single owner of the LENGTH-truncated-tool-call retry options copy —
     * previously duplicated in both loops. Rebuilds the request options with a
     * boosted {@code maxCompletionTokens} (2^attempt × base, capped).
     *
     * @see ResponseRecoveryPolicy#boostedMaxTokens(int, int)
     */
    public static com.azhukov.agent.core.client.ModelRequestOptions withBoostedMaxTokens(
            com.azhukov.agent.core.client.ModelRequestOptions options, int boostedMax) {
        return new com.azhukov.agent.core.client.ModelRequestOptions(
            options.modelName(), options.reasoningEffort(),
            options.fastMode(), options.voiceMode(),
            options.personality(), options.subgoal(),
            boostedMax);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Backoff sleep
    // ──────────────────────────────────────────────────────────────────

    /**
     * Sleep for the given delay in 200ms increments, checking for thread
     * interrupts between each chunk. Mirrors Hermes backoff sleep.
     * <p>
     * This allows the agent to respond to interrupts (user cancellation,
     * session teardown) promptly instead of blocking for the full backoff
     * duration.
     *
     * @param delayMs total sleep time in milliseconds
     * @throws InterruptedException if the thread was interrupted during sleep
     */
    public static void interruptibleSleep(long delayMs) throws InterruptedException {
        // M3 fix: Thread.sleep already throws InterruptedException when the flag is set,
        // and re-checking with Thread.interrupted() would CLEAR the flag and swallow
        // the interrupt. Sleep in bounded chunks and let sleep() itself propagate.
        long remaining = delayMs;
        while (remaining > 0) {
            long chunk = Math.min(200, remaining);
            Thread.sleep(chunk);
            remaining -= chunk;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Refusal pattern detection
    // ──────────────────────────────────────────────────────────────────

    /**
     * Detect refusal patterns in the error message that indicate a content
     * policy violation. Returns a user-friendly message if a refusal pattern
     * is found, null otherwise.
     */
    public static String detectRefusalPattern(String message) {
        if (message == null) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("i cannot") || lower.contains("i can't")
            || lower.contains("i'm unable to") || lower.contains("i am unable to")
            || lower.contains("i'm not able to") || lower.contains("i am not able to")
            || lower.contains("i won't be able to") || lower.contains("i will not be able to")) {
            return "The model declined to generate a response for this request due to a content policy restriction. " +
                   "Please rephrase your request or try a different approach.";
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Token estimation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Estimate output tokens from a {@link ChatResponse}.
     */
    public static int estimateResponseTokens(ChatResponse response) {
        int chars = response.content() != null ? response.content().length() : 0;
        if (response.toolCalls() != null) {
            for (ToolCall tc : response.toolCalls()) {
                chars += tc.arguments() != null ? tc.arguments().length() : 0;
                chars += tc.name() != null ? tc.name().length() : 0;
            }
        }
        return chars / 4 + 1;
    }

    /**
     * Estimate output tokens from raw content + tool calls (streaming path).
     */
    public static int estimateResponseTokens(String content, List<ToolCall> toolCalls) {
        int chars = content != null ? content.length() : 0;
        if (toolCalls != null) {
            for (ToolCall tc : toolCalls) {
                chars += tc.arguments() != null ? tc.arguments().length() : 0;
                chars += tc.name() != null ? tc.name().length() : 0;
            }
        }
        return chars / 4 + 1;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Grammar pattern sanitization (llama.cpp Guard 7)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Strip {@code pattern} and {@code format} JSON Schema keywords from tool schemas.
     * <p>
     * Hermes parity (tools/schema_sanitizer.py:550): reactive sanitizer invoked
     * only when llama.cpp's json-schema-to-grammar converter rejects a tool
     * schema with HTTP 400. llama.cpp's regex engine supports only a small
     * subset of ECMAScript regex — it rejects escape classes like \d, \w, \s
     * and most format values. Cloud providers accept these fine, so we keep
     * them by default and only strip on demand.
     * <p>
     * Only strips as a sibling of {@code type}/{@code anyOf}/{@code oneOf}/
     * {@code allOf} — avoids stripping literal property keys named "pattern".
     */
    public static List<ToolDefinition> stripGrammarPatternsFromTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) return tools;
        var stripped = new ArrayList<ToolDefinition>(tools.size());
        int count = 0;
        for (ToolDefinition tool : tools) {
            var newParams = new HashMap<String, Object>(tool.parameters());
            count += stripPatternAndFormat(newParams);
            stripped.add(new ToolDefinition(tool.name(), tool.description(), Map.copyOf(newParams)));
        }
        log.info("stripGrammarPatternsFromTools: stripped {} pattern/format keywords from {} tools", count, tools.size());
        return stripped;
    }

    /**
     * Strip {@code pattern} and {@code format} JSON Schema keywords from a
     * schema map, recursively. Returns the number of keywords stripped.
     * <p>
     * Only strips as a sibling of {@code type}/{@code anyOf}/{@code oneOf}/
     * {@code allOf} — avoids stripping literal property keys named "pattern".
     */
    @SuppressWarnings("unchecked")
    public static int stripPatternAndFormat(Map<String, Object> schema) {
        int stripped = 0;
        boolean isSchemaNode = schema.containsKey("type") || schema.containsKey("anyOf")
            || schema.containsKey("oneOf") || schema.containsKey("allOf");
        for (String key : new ArrayList<>(schema.keySet())) {
            if (isSchemaNode && ("pattern".equals(key) || "format".equals(key))) {
                schema.remove(key);
                stripped++;
                continue;
            }
            Object value = schema.get(key);
            if (value instanceof Map<?, ?> map) {
                stripped += stripPatternAndFormat((Map<String, Object>) map);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        stripped += stripPatternAndFormat((Map<String, Object>) m);
                    }
                }
            }
        }
        return stripped;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Thinking blocks
    // ──────────────────────────────────────────────────────────────────

    /**
     * Check if any message in the context contains thinking/reasoning blocks.
     */
    public static boolean containsThinkingBlocks(List<Message> context) {
        return context.stream().anyMatch(m -> m.content() != null
            && ThinkBlockProcessor.containsAnyThinkTag(m.content()));
    }

    // ──────────────────────────────────────────────────────────────────
    //  Image content
    // ──────────────────────────────────────────────────────────────────

    /**
     * Check if any message in the context contains image content (imageCount > 0).
     */
    public static boolean containsImageContent(List<Message> context) {
        return context.stream().anyMatch(m -> m.imageCount() != null && m.imageCount() > 0);
    }

    /**
     * Strip image content from all messages (sets imageCount to 0).
     */
    public static List<Message> stripImageContent(List<Message> context) {
        return context.stream().map(m -> {
            if (m.imageCount() == null || m.imageCount() == 0) return m;
            return new Message(m.role(), m.content(), m.toolCall(), m.toolCalls(),
                m.toolCallId(), m.turnIndex(), 0);
        }).toList();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Multimodal tool content
    // ──────────────────────────────────────────────────────────────────

    /**
     * Check if any message in the context contains multimodal tool content
     * (data: URIs with image/ or base64 content).
     */
    public static boolean containsMultimodalToolContent(List<Message> context) {
        return context.stream().anyMatch(m -> m.content() != null
            && m.content().startsWith("data:")
            && (m.content().contains("image/") || m.content().contains(";base64,")));
    }

    /**
     * Strip multimodal tool content from all messages (replaces data: URIs
     * with a placeholder).
     */
    public static List<Message> stripMultimodalToolContent(List<Message> context) {
        return context.stream().map(m -> {
            if (m.content() != null && m.content().startsWith("data:")) {
                return new Message(m.role(), "[multimodal content stripped]", m.toolCall(),
                    m.toolCalls(), m.toolCallId(), m.turnIndex(), m.imageCount());
            }
            return m;
        }).toList();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Retry-After header parsing
    // ──────────────────────────────────────────────────────────────────

    /**
     * Extract Retry-After header value from an HTTP exception, if available.
     *
     * @param e the exception from the model call
     * @return retry-after value in milliseconds, or -1 if not found
     */
    public static long extractRetryAfterMs(Exception e) {
        if (e == null || e.getMessage() == null) {
            return -1;
        }
        String msg = e.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("retry-after:");
        if (idx >= 0) {
            String after = msg.substring(idx + 12).trim();
            String[] parts = after.split("[\\s,;]");
            for (String part : parts) {
                try {
                    double seconds = Double.parseDouble(part.trim());
                    return (long) (seconds * 1000);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // Hermes parity (run_agent retry ladder): LiteLLM-style proxy errors embed
        // the cooldown in the BODY ("No deployments available ... Try again in 600
        // seconds"), with no Retry-After header. Blind exponential backoff retried
        // at 1s/2s/4s against a 10-minute cooldown — 5 guaranteed failures and a
        // ~90s user wait for nothing. Parse the body hint and honor it.
        java.util.regex.Matcher m = TRY_AGAIN_SECONDS.matcher(msg);
        if (m.find()) {
            try {
                return (long) (Double.parseDouble(m.group(1)) * 1000);
            } catch (NumberFormatException ignored) {
            }
        }
        idx = lower.indexOf("retry-after");
        if (idx >= 0) {
            String after = msg.substring(idx + 11).trim();
            if (after.startsWith(":")) {
                after = after.substring(1).trim();
            }
            String[] parts = after.split("[\\s,;]");
            for (String part : parts) {
                try {
                    double seconds = Double.parseDouble(part.trim());
                    return (long) (seconds * 1000);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Lowercase message check
    // ──────────────────────────────────────────────────────────────────

    /**
     * Check if an exception's message contains a substring (case-insensitive).
     */
    public static boolean lowerMessageContains(Exception e, String substring) {
        return e != null && e.getMessage() != null
            && e.getMessage().toLowerCase(Locale.ROOT).contains(substring.toLowerCase(Locale.ROOT));
    }

    // ──────────────────────────────────────────────────────────────────
    //  Compression-failure classification
    // ──────────────────────────────────────────────────────────────────

    /**
     * Classify an error message for log output during compression-failure
     * cooldown handling.
     */
    public static String classifyForLog(String msg) {
        if (msg.contains("timeout") || msg.contains("timed out")) return "timeout ladder";
        if (msg.contains("json") || msg.contains("stream") && msg.contains("closed")) return "json/stream transient";
        if (isTransient(msg)) return "network transient";
        return "hard failure";
    }

    /**
     * Check if an error message indicates a transient network failure.
     */
    public static boolean isTransient(String msg) {
        return msg.contains("connection") || msg.contains("reset") || msg.contains("refused")
            || msg.contains("broken pipe") || msg.contains("eof") || msg.contains("closed");
    }
}