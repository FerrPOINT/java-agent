package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity tests for HistorySanitizer — the Gemini/litellm "Missing
 * corresponding tool call for tool response message" failure mode observed
 * in production after context compression dropped an assistant tool_call
 * but kept its screenshot tool-result.
 */
class HistorySanitizerTest {

    private static ToolCall tc(String id) {
        return new ToolCall(id, "browser_vision", "{}");
    }

    @Test
    void dropsOrphanToolResultAfterCompression() {
        // Compressor dropped the assistant tool_call but kept the tool result
        List<Message> history = List.of(
            Message.user("посмотри страницу"),
            Message.toolResult("call_873", "data:image/png;base64,iVBOR...", 1)
        );

        List<Message> out = HistorySanitizer.sanitize(history);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).role()).isEqualTo(Role.USER);
    }

    @Test
    void keepsPairedToolCallAndResult() {
        List<Message> history = List.of(
            Message.user("посмотри"),
            Message.assistantToolCalls(List.of(tc("call_1")), 1),
            Message.toolResult("call_1", "screenshot data", 1),
            Message.assistant("вот что я увидел", 2)
        );

        List<Message> out = HistorySanitizer.sanitize(history);

        // No repairs — same reference, nothing dropped
        assertThat(out).isSameAs(history);
    }

    @Test
    void keepsPairAcrossUserBoundary() {
        // assistant(toolCalls) + tool result, then a user message — the
        // "user redirected before continuation" pattern. Valid; must survive.
        List<Message> history = List.of(
            Message.assistantToolCalls(List.of(tc("call_1")), 1),
            Message.toolResult("call_1", "result", 1),
            Message.user("нет, другое")
        );

        List<Message> out = HistorySanitizer.sanitize(history);

        assertThat(out).isSameAs(history);
    }

    @Test
    void dropsDuplicateToolResultForSameCallId() {
        List<Message> history = List.of(
            Message.assistantToolCalls(List.of(tc("call_1")), 1),
            Message.toolResult("call_1", "first", 1),
            Message.toolResult("call_1", "duplicate from retry", 1)
        );

        List<Message> out = HistorySanitizer.sanitize(history);

        assertThat(out).hasSize(2);
        assertThat(out.get(1).content()).isEqualTo("first");
    }

    @Test
    void dropsOrphanAfterUserTurnResetsKnownIds() {
        List<Message> history = List.of(
            Message.assistantToolCalls(List.of(tc("call_1")), 1),
            Message.toolResult("call_1", "ok", 1),
            Message.user("ещё раз"),
            // Stray tool result referencing an id from a previous run
            Message.toolResult("call_1", "stale", 2)
        );

        List<Message> out = HistorySanitizer.sanitize(history);

        assertThat(out).hasSize(3);
        assertThat(out.stream().noneMatch(m -> "stale".equals(m.content()))).isTrue();
    }

    @Test
    void mergesConsecutiveUserMessages() {
        List<Message> history = List.of(
            Message.user("первое"),
            Message.user("второе")
        );

        List<Message> out = HistorySanitizer.sanitize(history);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).content()).isEqualTo("первое\n\nвторое");
    }

    @Test
    void productionTrace_geminiScreenshotCase() {
        // Exact shape from production 2026-08-21 15:50: user → assistant(toolCalls)
        // → tool(screenshot) → [compressor drops assistant] → model call fails
        List<Message> history = new java.util.ArrayList<>(List.of(
            Message.system("sys"),
            Message.user("открой сайт"),
            Message.assistantToolCalls(List.of(tc("call_873e1e8674f549ca99de1bbc")), 1),
            Message.toolResult("call_873e1e8674f549ca99de1bbc",
                "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA... (2MB)", 1)
        ));
        // Simulate compression dropping the assistant tool_call message
        history.remove(2);

        List<Message> out = HistorySanitizer.sanitize(history);

        // system + user survive; the orphan screenshot tool-result is dropped
        assertThat(out).hasSize(2);
        assertThat(out.stream().noneMatch(m -> m.role() == Role.TOOL)).isTrue();
    }

    @Test
    void mergesConsecutiveAssistantMessages() {
        // Production 2026-08-21 16:50: Gemini 400 "Please ensure that function
        // call turn comes immediately after a user turn or after a function
        // response turn" — two adjacent assistant(toolCalls) turns with the
        // intermediate tool result dropped by compression.
        List<Message> history = List.of(
            Message.user("проверь браузер"),
            Message.assistantToolCalls(List.of(tc("call_a")), 1),
            Message.assistantToolCalls(List.of(tc("call_b")), 1),
            Message.toolResult("call_b", "vision ok", 1),
            Message.assistant("готово", 2)
        );

        List<Message> out = HistorySanitizer.sanitize(history);

        // call_a and call_b merge into one assistant turn; its result stays
        assertThat(out).hasSize(4);
        Message mergedTurn = out.get(1);
        assertThat(mergedTurn.role()).isEqualTo(Role.ASSISTANT);
        assertThat(mergedTurn.toolCalls()).extracting(ToolCall::id)
            .containsExactly("call_a", "call_b");
        assertThat(out.get(2).toolCallId()).isEqualTo("call_b");
    }

    @Test
    void assistantTextAndToolCallsMergeKeepsContent() {
        List<Message> history = List.of(
            Message.user("тест"),
            Message.assistant("частичный текст", 1),
            Message.assistantToolCalls(List.of(tc("call_x")), 1),
            Message.toolResult("call_x", "результат", 1)
        );

        List<Message> out = HistorySanitizer.sanitize(history);

        assertThat(out).hasSize(3);
        assertThat(out.get(1).content()).isEqualTo("частичный текст");
        assertThat(out.get(1).toolCalls()).extracting(ToolCall::id).containsExactly("call_x");
    }
}