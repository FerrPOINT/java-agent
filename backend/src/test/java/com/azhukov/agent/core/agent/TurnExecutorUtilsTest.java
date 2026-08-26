package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused unit tests for {@link TurnExecutorUtils} — the pure static utility
 * methods extracted from {@link TurnExecutor}.
 * <p>
 * These tests exercise each method directly without any Spring context or
 * instance dependencies, since {@code TurnExecutorUtils} contains only pure
 * static functions.
 */
@DisplayName("TurnExecutorUtils")
class TurnExecutorUtilsTest {

    // ── detectRefusalPattern ──────────────────────────────────────────

    @Nested
    @DisplayName("detectRefusalPattern")
    class DetectRefusalPattern {

        @Test
        @DisplayName("returns null for null input")
        void nullInput() {
            assertThat(TurnExecutorUtils.detectRefusalPattern(null)).isNull();
        }

        @Test
        @DisplayName("returns null for a non-refusal message")
        void nonRefusal() {
            assertThat(TurnExecutorUtils.detectRefusalPattern("Internal server error"))
                .isNull();
        }

        @Test
        @DisplayName("detects 'I cannot' pattern")
        void detectsICannot() {
            String result = TurnExecutorUtils.detectRefusalPattern("I cannot help with that request.");
            assertThat(result).isNotNull();
            assertThat(result).contains("content policy restriction");
        }

        @Test
        @DisplayName("detects 'I can't' pattern (case-insensitive)")
        void detectsICant() {
            assertThat(TurnExecutorUtils.detectRefusalPattern("i can't provide that information."))
                .isNotNull();
        }

        @Test
        @DisplayName("detects 'I'm unable to' pattern")
        void detectsUnableTo() {
            assertThat(TurnExecutorUtils.detectRefusalPattern("I'm unable to assist with this."))
                .isNotNull();
        }

        @Test
        @DisplayName("detects 'I am unable to' pattern")
        void detectsIAmUnableTo() {
            assertThat(TurnExecutorUtils.detectRefusalPattern("I am unable to fulfill this request."))
                .isNotNull();
        }

        @Test
        @DisplayName("detects 'I will not be able to' pattern")
        void detectsWillNotBeAbleTo() {
            assertThat(TurnExecutorUtils.detectRefusalPattern("I will not be able to help with this."))
                .isNotNull();
        }

        @Test
        @DisplayName("returns a user-friendly message on match")
        void friendlyMessage() {
            String result = TurnExecutorUtils.detectRefusalPattern("I cannot comply with this request.");
            assertThat(result)
                .contains("The model declined")
                .contains("rephrase");
        }
    }

    // ── estimateResponseTokens ───────────────────────────────────────

    @Nested
    @DisplayName("estimateResponseTokens")
    class EstimateResponseTokens {

        @Test
        @DisplayName("estimates from ChatResponse with text content only")
        void textOnly() {
            ChatResponse response = new ChatResponse("hello world", List.of());
            // 11 chars / 4 + 1 = 3
            assertThat(TurnExecutorUtils.estimateResponseTokens(response)).isEqualTo(3);
        }

        @Test
        @DisplayName("estimates from ChatResponse with null content")
        void nullContent() {
            ChatResponse response = ChatResponse.text("");
            // 0 chars / 4 + 1 = 1
            assertThat(TurnExecutorUtils.estimateResponseTokens(response)).isEqualTo(1);
        }

        @Test
        @DisplayName("estimates from ChatResponse with tool calls")
        void withToolCalls() {
            ToolCall tc = new ToolCall("call_1", "search", "{\"query\":\"test\"}");
            ChatResponse response = new ChatResponse("hi", List.of(tc));
            // content=2 + tc.arguments=16 + tc.name=6 = 24; 24/4+1 = 7
            assertThat(TurnExecutorUtils.estimateResponseTokens(response)).isEqualTo(7);
        }

        @Test
        @DisplayName("estimates from raw content + tool calls (streaming overload)")
        void streamingOverload() {
            ToolCall tc = new ToolCall("call_1", "search", "{\"q\":\"a\"}");
            int tokens = TurnExecutorUtils.estimateResponseTokens("hello", List.of(tc));
            // content=5 + tc.arguments=9 + tc.name=6 = 20; 20/4+1 = 6
            assertThat(tokens).isEqualTo(6);
        }

        @Test
        @DisplayName("handles null tool calls list")
        void nullToolCalls() {
            int tokens = TurnExecutorUtils.estimateResponseTokens("hello", null);
            // 5/4+1 = 2
            assertThat(tokens).isEqualTo(2);
        }

        @Test
        @DisplayName("handles null content string")
        void nullContentString() {
            int tokens = TurnExecutorUtils.estimateResponseTokens(null, List.of());
            // 0/4+1 = 1
            assertThat(tokens).isEqualTo(1);
        }
    }

    // ── stripGrammarPatternsFromTools / stripPatternAndFormat ────────

    @Nested
    @DisplayName("stripGrammarPatternsFromTools / stripPatternAndFormat")
    class StripGrammarPatterns {

        @Test
        @DisplayName("returns same list for null or empty")
        void nullOrEmpty() {
            assertThat(TurnExecutorUtils.stripGrammarPatternsFromTools(null)).isNull();
            assertThat(TurnExecutorUtils.stripGrammarPatternsFromTools(List.of())).isEmpty();
        }

        @Test
        @DisplayName("strips pattern and format from schema nodes")
        void stripsPatternAndFormat() {
            Map<String, Object> emailSchema = new java.util.HashMap<>(Map.of(
                "type", "string", "pattern", "^[^@]+@[^@]+$", "format", "email"));
            Map<String, Object> properties = new java.util.HashMap<>();
            properties.put("email", emailSchema);
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("type", "object");
            params.put("properties", properties);
            ToolDefinition tool = new ToolDefinition("test", "test", params);
            List<ToolDefinition> result = TurnExecutorUtils.stripGrammarPatternsFromTools(List.of(tool));
            assertThat(result).hasSize(1);
            // pattern and format should be gone from the nested schema
            Map<String, Object> strippedProps = (Map<String, Object>) result.get(0).parameters().get("properties");
            Map<String, Object> strippedEmailSchema = (Map<String, Object>) strippedProps.get("email");
            assertThat(strippedEmailSchema).doesNotContainKey("pattern");
            assertThat(strippedEmailSchema).doesNotContainKey("format");
            assertThat(strippedEmailSchema).containsKey("type");
        }

        @Test
        @DisplayName("does not strip 'pattern' as a property key name")
        void preservesPatternAsPropertyName() {
            Map<String, Object> params = Map.of(
                "type", "object",
                "properties", Map.of(
                    "pattern", Map.of("type", "string")  // "pattern" is a property name, not a keyword
                )
            );
            ToolDefinition tool = new ToolDefinition("test", "test", params);
            List<ToolDefinition> result = TurnExecutorUtils.stripGrammarPatternsFromTools(List.of(tool));
            Map<String, Object> props = (Map<String, Object>) result.get(0).parameters().get("properties");
            // The property named "pattern" should still be there
            assertThat(props).containsKey("pattern");
        }

        @Test
        @DisplayName("stripPatternAndFormat returns count of stripped keywords")
        void returnsCount() {
            Map<String, Object> schema = new java.util.HashMap<>(Map.of(
                "type", "string",
                "pattern", "abc",
                "format", "date"
            ));
            int count = TurnExecutorUtils.stripPatternAndFormat(schema);
            assertThat(count).isEqualTo(2);
            assertThat(schema).doesNotContainKey("pattern");
            assertThat(schema).doesNotContainKey("format");
        }

        @Test
        @DisplayName("strips recursively through nested anyOf")
        void recursiveAnyOf() {
            Map<String, Object> uriSchema = new java.util.HashMap<>(Map.of("type", "string", "format", "uri"));
            Map<String, Object> numberSchema = new java.util.HashMap<>(Map.of("type", "number", "pattern", "\\d+"));
            Map<String, Object> schema = new java.util.HashMap<>();
            schema.put("anyOf", List.of(uriSchema, numberSchema));
            int count = TurnExecutorUtils.stripPatternAndFormat(schema);
            assertThat(count).isEqualTo(2);
        }
    }

    // ── containsThinkingBlocks ───────────────────────────────────────

    @Nested
    @DisplayName("containsThinkingBlocks")
    class ContainsThinkingBlocks {

        @Test
        @DisplayName("returns true for message with think block")
        void hasThinkBlock() {
            List<Message> context = List.of(
                Message.user("hello"),
                Message.assistant("<thinking>reasoning here</thinking>answer", 0)
            );
            assertThat(TurnExecutorUtils.containsThinkingBlocks(context)).isTrue();
        }

        @Test
        @DisplayName("returns false for messages without think blocks")
        void noThinkBlock() {
            List<Message> context = List.of(
                Message.user("hello"),
                Message.assistant("just a normal answer", 0)
            );
            assertThat(TurnExecutorUtils.containsThinkingBlocks(context)).isFalse();
        }

        @Test
        @DisplayName("returns false for empty context")
        void emptyContext() {
            assertThat(TurnExecutorUtils.containsThinkingBlocks(List.of())).isFalse();
        }
    }

    // ── containsImageContent / stripImageContent ──────────────────────

    @Nested
    @DisplayName("containsImageContent / stripImageContent")
    class ImageContent {

        @Test
        @DisplayName("containsImageContent returns true when imageCount > 0")
        void containsImages() {
            List<Message> context = List.of(
                Message.user("hello"),
                Message.userWithImages("look at this", 2)
            );
            assertThat(TurnExecutorUtils.containsImageContent(context)).isTrue();
        }

        @Test
        @DisplayName("containsImageContent returns false when no images")
        void noImages() {
            List<Message> context = List.of(
                Message.user("hello"),
                Message.assistant("world", 0)
            );
            assertThat(TurnExecutorUtils.containsImageContent(context)).isFalse();
        }

        @Test
        @DisplayName("stripImageContent sets imageCount to 0")
        void stripsImages() {
            List<Message> context = List.of(
                Message.userWithImages("look at this", 3)
            );
            List<Message> stripped = TurnExecutorUtils.stripImageContent(context);
            assertThat(stripped).hasSize(1);
            assertThat(stripped.get(0).imageCount()).isZero();
            assertThat(stripped.get(0).content()).isEqualTo("look at this");
        }

        @Test
        @DisplayName("stripImageContent leaves non-image messages unchanged")
        void leavesNonImageMessages() {
            Message msg = Message.user("plain text");
            List<Message> stripped = TurnExecutorUtils.stripImageContent(List.of(msg));
            assertThat(stripped.get(0).imageCount()).isZero();
            assertThat(stripped.get(0).content()).isEqualTo("plain text");
        }
    }

    // ── containsMultimodalToolContent / stripMultimodalToolContent ──

    @Nested
    @DisplayName("containsMultimodalToolContent / stripMultimodalToolContent")
    class MultimodalContent {

        @Test
        @DisplayName("containsMultimodalToolContent detects data: URI with image/")
        void detectsDataImage() {
            List<Message> context = List.of(
                new Message(com.azhukov.agent.core.model.Role.ASSISTANT, "data:image/png;base64,abc123==", null, null, "tc1", 0, 0)
            );
            assertThat(TurnExecutorUtils.containsMultimodalToolContent(context)).isTrue();
        }

        @Test
        @DisplayName("containsMultimodalToolContent detects data: URI with ;base64,")
        void detectsDataBase64() {
            List<Message> context = List.of(
                new Message(com.azhukov.agent.core.model.Role.ASSISTANT, "data:application/octet-stream;base64,abc=", null, null, "tc1", 0, 0)
            );
            assertThat(TurnExecutorUtils.containsMultimodalToolContent(context)).isTrue();
        }

        @Test
        @DisplayName("containsMultimodalToolContent returns false for plain text")
        void noMultimodal() {
            List<Message> context = List.of(
                Message.assistant("just plain text", 0)
            );
            assertThat(TurnExecutorUtils.containsMultimodalToolContent(context)).isFalse();
        }

        @Test
        @DisplayName("stripMultimodalToolContent replaces data: URIs with placeholder")
        void stripsMultimodal() {
            List<Message> context = List.of(
                new Message(com.azhukov.agent.core.model.Role.ASSISTANT, "data:image/png;base64,abc123==", null, null, "tc1", 0, 0),
                Message.assistant("normal text", 0)
            );
            List<Message> stripped = TurnExecutorUtils.stripMultimodalToolContent(context);
            assertThat(stripped).hasSize(2);
            assertThat(stripped.get(0).content()).isEqualTo("[multimodal content stripped]");
            assertThat(stripped.get(1).content()).isEqualTo("normal text");
        }
    }

    // ── extractRetryAfterMs ───────────────────────────────────────────

    @Nested
    @DisplayName("extractRetryAfterMs")
    class ExtractRetryAfterMs {

        @Test
        @DisplayName("returns -1 for null exception")
        void nullException() {
            assertThat(TurnExecutorUtils.extractRetryAfterMs(null)).isEqualTo(-1);
        }

        @Test
        @DisplayName("returns -1 for exception with null message")
        void nullMessage() {
            assertThat(TurnExecutorUtils.extractRetryAfterMs(new RuntimeException())).isEqualTo(-1);
        }

        @Test
        @DisplayName("parses 'Retry-After: 5' header")
        void parsesRetryAfter() {
            Exception e = new RuntimeException("HTTP 429 Too Many Requests\nRetry-After: 5");
            assertThat(TurnExecutorUtils.extractRetryAfterMs(e)).isEqualTo(5000);
        }

        @Test
        @DisplayName("parses 'retry-after: 10' (lowercase)")
        void parsesLowercase() {
            Exception e = new RuntimeException("429 rate limited\nretry-after: 10");
            assertThat(TurnExecutorUtils.extractRetryAfterMs(e)).isEqualTo(10_000);
        }

        @Test
        @DisplayName("parses 'Retry-After 3' without colon")
        void parsesWithoutColon() {
            Exception e = new RuntimeException("429\nRetry-After 3 seconds");
            assertThat(TurnExecutorUtils.extractRetryAfterMs(e)).isEqualTo(3000);
        }

        @Test
        @DisplayName("returns -1 when no Retry-After header")
        void noHeader() {
            Exception e = new RuntimeException("Internal server error");
            assertThat(TurnExecutorUtils.extractRetryAfterMs(e)).isEqualTo(-1);
        }

        @Test
        @DisplayName("parses fractional seconds")
        void parsesFractional() {
            Exception e = new RuntimeException("Retry-After: 0.5");
            assertThat(TurnExecutorUtils.extractRetryAfterMs(e)).isEqualTo(500);
        }
    }

    // ── lowerMessageContains ─────────────────────────────────────────

    @Nested
    @DisplayName("lowerMessageContains")
    class LowerMessageContains {

        @Test
        @DisplayName("returns true when substring is present (case-insensitive)")
        void containsSubstring() {
            Exception e = new RuntimeException("Long Context Beta is required");
            assertThat(TurnExecutorUtils.lowerMessageContains(e, "long context beta")).isTrue();
        }

        @Test
        @DisplayName("returns false when substring is absent")
        void doesNotContain() {
            Exception e = new RuntimeException("Some other error");
            assertThat(TurnExecutorUtils.lowerMessageContains(e, "long context beta")).isFalse();
        }

        @Test
        @DisplayName("returns false for null exception")
        void nullException() {
            assertThat(TurnExecutorUtils.lowerMessageContains(null, "test")).isFalse();
        }

        @Test
        @DisplayName("returns false for null message")
        void nullMessage() {
            assertThat(TurnExecutorUtils.lowerMessageContains(new RuntimeException(), "test")).isFalse();
        }

        @Test
        @DisplayName("is case-insensitive on both sides")
        void caseInsensitive() {
            Exception e = new RuntimeException("TIMEOUT occurred");
            assertThat(TurnExecutorUtils.lowerMessageContains(e, "timeout")).isTrue();
        }
    }

    // ── classifyForLog ────────────────────────────────────────────────

    @Nested
    @DisplayName("classifyForLog")
    class ClassifyForLog {

        @Test
        @DisplayName("classifies timeout as 'timeout ladder'")
        void timeout() {
            assertThat(TurnExecutorUtils.classifyForLog("request timeout after 30s")).isEqualTo("timeout ladder");
        }

        @Test
        @DisplayName("classifies 'timed out' as 'timeout ladder'")
        void timedOut() {
            assertThat(TurnExecutorUtils.classifyForLog("request timed out")).isEqualTo("timeout ladder");
        }

        @Test
        @DisplayName("classifies json/stream closed as 'json/stream transient'")
        void jsonStream() {
            assertThat(TurnExecutorUtils.classifyForLog("json decode error")).isEqualTo("json/stream transient");
        }

        @Test
        @DisplayName("classifies connection reset as 'network transient'")
        void networkTransient() {
            assertThat(TurnExecutorUtils.classifyForLog("connection reset by peer")).isEqualTo("network transient");
        }

        @Test
        @DisplayName("classifies unknown as 'hard failure'")
        void hardFailure() {
            assertThat(TurnExecutorUtils.classifyForLog("some unexpected error")).isEqualTo("hard failure");
        }
    }

    // ── isTransient ──────────────────────────────────────────────────

    @Nested
    @DisplayName("isTransient")
    class IsTransient {

        @Test
        @DisplayName("detects 'connection'")
        void connection() {
            assertThat(TurnExecutorUtils.isTransient("connection refused")).isTrue();
        }

        @Test
        @DisplayName("detects 'reset'")
        void reset() {
            assertThat(TurnExecutorUtils.isTransient("connection reset")).isTrue();
        }

        @Test
        @DisplayName("detects 'refused'")
        void refused() {
            assertThat(TurnExecutorUtils.isTransient("connection refused")).isTrue();
        }

        @Test
        @DisplayName("detects 'broken pipe'")
        void brokenPipe() {
            assertThat(TurnExecutorUtils.isTransient("broken pipe")).isTrue();
        }

        @Test
        @DisplayName("detects 'eof'")
        void eof() {
            assertThat(TurnExecutorUtils.isTransient("unexpected eof")).isTrue();
        }

        @Test
        @DisplayName("detects 'closed'")
        void closed() {
            assertThat(TurnExecutorUtils.isTransient("stream closed")).isTrue();
        }

        @Test
        @DisplayName("returns false for non-transient message")
        void notTransient() {
            assertThat(TurnExecutorUtils.isTransient("internal server error")).isFalse();
        }
    }

    // ── interruptibleSleep ───────────────────────────────────────────

    @Nested
    @DisplayName("interruptibleSleep")
    class InterruptibleSleep {

        @Test
        @DisplayName("completes normally when not interrupted")
        void completesNormally() throws InterruptedException {
            long start = System.currentTimeMillis();
            TurnExecutorUtils.interruptibleSleep(300);
            long elapsed = System.currentTimeMillis() - start;
            // timing-assertion: verifies actual sleep duration of interruptibleSleep
            assertThat(elapsed).isGreaterThanOrEqualTo(200);
        }

        @Test
        @DisplayName("zero delay completes immediately")
        void zeroDelay() throws InterruptedException {
            TurnExecutorUtils.interruptibleSleep(0);
        }

        @Test
        @DisplayName("negative delay completes immediately")
        void negativeDelay() throws InterruptedException {
            TurnExecutorUtils.interruptibleSleep(-100);
        }

        @Test
        @DisplayName("throws InterruptedException when thread is interrupted")
        void throwsOnInterrupt() {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> TurnExecutorUtils.interruptibleSleep(10_000))
                .isInstanceOf(InterruptedException.class);
        }

        @Test
        @DisplayName("large delay sleeps in 200ms chunks and responds to interrupt")
        void chunked() throws InterruptedException {
            AtomicBoolean wasInterrupted = new AtomicBoolean(false);
            CountDownLatch sleeperStarted = new CountDownLatch(1);
            Thread sleeper = new Thread(() -> {
                try {
                    sleeperStarted.countDown();
                    TurnExecutorUtils.interruptibleSleep(10_000);
                } catch (InterruptedException e) {
                    wasInterrupted.set(true);
                }
            });
            sleeper.start();
            // Wait until the sleeper has entered interruptibleSleep
            sleeperStarted.await();
            sleeper.interrupt();
            sleeper.join(2000);
            assertThat(wasInterrupted.get()).isTrue();
        }
    }

    // ── TurnExecutor delegation sanity ───────────────────────────────

    @Nested
    @DisplayName("TurnExecutor delegation (backward compatibility)")
    class TurnExecutorDelegation {

        @Test
        @DisplayName("TurnExecutor.detectRefusalPattern delegates to TurnExecutorUtils")
        void refusalDelegation() {
            assertThat(TurnExecutor.detectRefusalPattern("I cannot help"))
                .isEqualTo(TurnExecutorUtils.detectRefusalPattern("I cannot help"));
        }

        @Test
        @DisplayName("TurnExecutor.extractRetryAfterMs delegates to TurnExecutorUtils")
        void retryAfterDelegation() {
            Exception e = new RuntimeException("Retry-After: 5");
            assertThat(TurnExecutor.extractRetryAfterMs(e))
                .isEqualTo(TurnExecutorUtils.extractRetryAfterMs(e));
        }

        @Test
        @DisplayName("TurnExecutor.lowerMessageContains delegates to TurnExecutorUtils")
        void lowerMessageDelegation() {
            Exception e = new RuntimeException("connection timeout");
            assertThat(TurnExecutor.lowerMessageContains(e, "timeout"))
                .isEqualTo(TurnExecutorUtils.lowerMessageContains(e, "timeout"));
        }

        @Test
        @DisplayName("TurnExecutor.containsThinkingBlocks delegates to TurnExecutorUtils")
        void thinkingBlocksDelegation() {
            List<Message> context = List.of(Message.assistant("<think>test</think>", 0));
            assertThat(TurnExecutor.containsThinkingBlocks(context))
                .isEqualTo(TurnExecutorUtils.containsThinkingBlocks(context));
        }

        @Test
        @DisplayName("TurnExecutor.estimateResponseTokens delegates to TurnExecutorUtils")
        void estimateTokensDelegation() {
            ChatResponse response = new ChatResponse("hello", List.of());
            assertThat(TurnExecutor.estimateResponseTokens(response))
                .isEqualTo(TurnExecutorUtils.estimateResponseTokens(response));
        }
    }
}