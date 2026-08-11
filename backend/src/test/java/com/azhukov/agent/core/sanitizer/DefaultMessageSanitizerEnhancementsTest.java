package com.azhukov.agent.core.sanitizer;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the new P1-8 enhancements to {@link DefaultMessageSanitizer}:
 * - Surrogate character sanitization (lone surrogates → U+FFFD)
 * - Control character stripping
 * - Null content handling (assistant with only tool calls)
 * - Tool call field surrogate stripping
 */
class DefaultMessageSanitizerEnhancementsTest {

    private final DefaultMessageSanitizer sanitizer = new DefaultMessageSanitizer();

    // ─── Surrogate sanitization ───

    @Nested
    @DisplayName("Surrogate character sanitization")
    class SurrogateSanitization {

        @Test
        @DisplayName("Lone surrogates in user content are replaced with U+FFFD")
        void loneSurrogatesReplaced() {
            var messages = List.of(
                Message.user("hello\ud800def"),
                Message.assistant("ok", 1)
            );

            var result = sanitizer.sanitize(messages);

            assertThat(result.get(0).content()).isEqualTo("hello\ufffddef");
        }

        @Test
        @DisplayName("Multiple lone surrogates are all replaced")
        void multipleSurrogatesReplaced() {
            // Use isolated surrogates separated by normal chars to avoid forming valid pairs
            var messages = List.of(
                Message.user("abc\ud800x\udc00yz\ud800abc"),
                Message.assistant("ok", 1)
            );

            var result = sanitizer.sanitize(messages);

            assertThat(result.get(0).content()).isEqualTo("abc\ufffdx\ufffdyz\ufffdabc");
        }

        @Test
        @DisplayName("Normal text without surrogates is unchanged")
        void normalTextUnchanged() {
            var messages = List.of(
                Message.user("Hello, world! 🌍"),
                Message.assistant("Hi!", 1)
            );

            var result = sanitizer.sanitize(messages);

            assertThat(result.get(0).content()).isEqualTo("Hello, world! 🌍");
        }

        @Test
        @DisplayName("Surrogates in system message are replaced")
        void surrogatesInSystemMessage() {
            var messages = List.of(
                Message.system("System\ud800prompt"),
                Message.user("hello"),
                Message.assistant("ok", 1)
            );

            var result = sanitizer.sanitize(messages);

            assertThat(result.get(0).content()).isEqualTo("System\ufffdprompt");
        }

        @Test
        @DisplayName("Surrogates in assistant message are replaced")
        void surrogatesInAssistantMessage() {
            var messages = List.of(
                Message.user("hello"),
                Message.assistant("response\ud800text", 1)
            );

            var result = sanitizer.sanitize(messages);

            assertThat(result.get(1).content()).isEqualTo("response\ufffdtext");
        }
    }

    // ─── Control character stripping ───

    @Nested
    @DisplayName("Control character stripping")
    class ControlCharStripping {

        @Test
        @DisplayName("Control characters (except \\n, \\r, \\t) are stripped")
        void controlCharsStripped() {
            var messages = List.of(
                Message.user("hello\u0000\u0001\u0002world"),
                Message.assistant("ok", 1)
            );

            var result = sanitizer.sanitize(messages);

            assertThat(result.get(0).content()).isEqualTo("helloworld");
        }

        @Test
        @DisplayName("Newlines, tabs, and carriage returns are preserved")
        void newlinesAndTabsPreserved() {
            var messages = List.of(
                Message.user("line1\nline2\tcol\r"),
                Message.assistant("ok", 1)
            );

            var result = sanitizer.sanitize(messages);

            assertThat(result.get(0).content()).isEqualTo("line1\nline2\tcol\r");
        }

        @Test
        @DisplayName("Mixed control chars and surrogates are both handled")
        void mixedControlAndSurrogates() {
            var messages = List.of(
                Message.user("text\u0000\ud800here\n"),
                Message.assistant("ok", 1)
            );

            var result = sanitizer.sanitize(messages);

            // \u0000 (control char) is removed, \ud800 (lone surrogate) is replaced with U+FFFD
            assertThat(result.get(0).content()).isEqualTo("text\ufffdhere\n");
        }
    }

    // ─── Null content handling ───

    @Nested
    @DisplayName("Null content handling")
    class NullContentHandling {

        @Test
        @DisplayName("Assistant message with null content and tool calls does not NPE")
        void nullContentWithToolCalls() {
            var messages = List.of(
                Message.user("do something"),
                Message.assistantToolCalls(List.of(
                    new ToolCall("c1", "read_file", "{\"path\":\"/tmp\"}")
                ), 1),
                Message.toolResult("c1", "result", 1),
                Message.assistant("done", 2)
            );

            var result = sanitizer.sanitize(messages);

            // Should not throw NPE and should produce valid output
            assertThat(result).isNotEmpty();
            assertThat(result.stream().anyMatch(m -> m.role() == Role.USER)).isTrue();
        }

        @Test
        @DisplayName("Consecutive assistant messages with null content don't NPE on merge")
        void consecutiveNullContentAssistants() {
            var messages = List.of(
                Message.user("do something"),
                Message.assistantToolCalls(List.of(
                    new ToolCall("c1", "read_file", "{}")
                ), 1),
                Message.assistantToolCalls(List.of(
                    new ToolCall("c2", "write_file", "{}")
                ), 2),
                Message.assistant("done", 3)
            );

            var result = sanitizer.sanitize(messages);

            assertThat(result).isNotEmpty();
        }
    }

    // ─── Tool call field sanitization ───

    @Nested
    @DisplayName("Tool call field sanitization")
    class ToolCallFieldSanitization {

        @Test
        @DisplayName("Surrogates in tool call ID are stripped")
        void surrogatesInToolCallId() {
            var messages = List.of(
                Message.user("do something"),
                Message.assistantWithToolCalls(null, List.of(
                    new ToolCall("id\ud800bad", "read_file", "{\"path\":\"/tmp\"}")
                ), 1),
                Message.toolResult("id\ud800bad", "result", 1),
                Message.assistant("done", 2)
            );

            var result = sanitizer.sanitize(messages);

            // Find the assistant message with tool calls
            var assistantWithCalls = result.stream()
                .filter(m -> m.toolCalls() != null && !m.toolCalls().isEmpty())
                .findFirst()
                .orElseThrow();
            assertThat(assistantWithCalls.toolCalls().get(0).id()).doesNotContain("\ud800");
        }

        @Test
        @DisplayName("Surrogates in tool call name are stripped")
        void surrogatesInToolCallName() {
            var messages = List.of(
                Message.user("do something"),
                Message.assistantWithToolCalls(null, List.of(
                    new ToolCall("c1", "read\ud800file", "{\"path\":\"/tmp\"}")
                ), 1),
                Message.toolResult("c1", "result", 1),
                Message.assistant("done", 2)
            );

            var result = sanitizer.sanitize(messages);

            var assistantWithCalls = result.stream()
                .filter(m -> m.toolCalls() != null && !m.toolCalls().isEmpty())
                .findFirst()
                .orElseThrow();
            assertThat(assistantWithCalls.toolCalls().get(0).name()).doesNotContain("\ud800");
        }

        @Test
        @DisplayName("Malformed tool call arguments are repaired")
        void malformedArgsRepaired() {
            var messages = List.of(
                Message.user("do something"),
                Message.assistantWithToolCalls(null, List.of(
                    new ToolCall("c1", "write_file", "{\"path\":\"/tmp\",}")
                ), 1),
                Message.toolResult("c1", "result", 1),
                Message.assistant("done", 2)
            );

            var result = sanitizer.sanitize(messages);

            var assistantWithCalls = result.stream()
                .filter(m -> m.toolCalls() != null && !m.toolCalls().isEmpty())
                .findFirst()
                .orElseThrow();
            // Trailing comma should be fixed
            String args = assistantWithCalls.toolCalls().get(0).arguments();
            assertThat(args).doesNotEndWith(",}");
        }
    }

    // ─── Empty list still rejected ───

    @Test
    @DisplayName("Null list is rejected with IllegalArgumentException")
    void nullListRejected() {
        assertThatThrownBy(() -> sanitizer.sanitize(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}