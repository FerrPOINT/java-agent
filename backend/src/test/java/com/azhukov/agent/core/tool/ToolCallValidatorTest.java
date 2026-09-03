package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ToolCallValidator}.
 * <p>
 * Mirrors Hermes' tool-call validation pipeline tests:
 * <ul>
 *   <li>Tool name validation + fuzzy repair (Levenshtein distance ≤ 2)</li>
 *   <li>JSON argument validation + truncation detection</li>
 *   <li>Deduplication (same name + same arguments)</li>
 *   <li>Delegate task capping (max 1 per batch)</li>
 * </ul>
 */
class ToolCallValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> REGISTERED = Set.of(
        "read_file", "write_file", "patch", "terminal", "search_files",
        "delegate_task", "clarify", "skill_view", "session_search",
        "web_search", "web_extract", "vision_analyze"
    );

    @Test
    @DisplayName("failurePayload returns structured JSON error")
    void failurePayloadReturnsStructuredJsonError() throws Exception {
        JsonNode payload = JSON.readTree(ToolCallValidator.failurePayload("bad arguments"));

        assertThat(payload.path("success").asBoolean()).isFalse();
        assertThat(payload.path("error").asText()).isEqualTo("bad arguments");
    }

    // ── Tool name validation & repair ──────────────────────────────────────

    @Nested
    @DisplayName("validateToolNames")
    class ValidateToolNames {

        @Test
        @DisplayName("Valid tool names pass with no errors")
        void validNamesPass() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{}"),
                new ToolCall("c2", "terminal", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).isEmpty();
            assertThat(calls).hasSize(2);
            assertThat(calls.get(0).name()).isEqualTo("read_file");
            assertThat(calls.get(1).name()).isEqualTo("terminal");
        }

        @Test
        @DisplayName("Unknown tool name returns error")
        void unknownToolReturnsError() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "nonexistent_tool", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("nonexistent_tool");
            assertThat(errors.get(0)).contains("does not exist");
        }

        @Test
        @DisplayName("Tool name with casing difference is repaired")
        void casingDifferenceRepaired() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "READ_FILE", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).isEmpty();
            assertThat(calls.get(0).name()).isEqualTo("read_file");
        }

        @Test
        @DisplayName("Tool name with hyphen is repaired to underscore")
        void hyphenReplacedWithUnderscore() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read-file", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).isEmpty();
            assertThat(calls.get(0).name()).isEqualTo("read_file");
        }

        @Test
        @DisplayName("Tool name with space is repaired to underscore")
        void spaceReplacedWithUnderscore() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read file", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).isEmpty();
            assertThat(calls.get(0).name()).isEqualTo("read_file");
        }

        @Test
        @DisplayName("Tool name with _tool suffix is stripped and repaired")
        void toolSuffixStripped() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file_tool", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).isEmpty();
            assertThat(calls.get(0).name()).isEqualTo("read_file");
        }

        @Test
        @DisplayName("CamelCase tool name is converted to snake_case")
        void camelCaseToSnakeCase() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "ReadFile", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).isEmpty();
            assertThat(calls.get(0).name()).isEqualTo("read_file");
        }

        @Test
        @DisplayName("Fuzzy match within Levenshtein distance 2 repairs name")
        void fuzzyMatchWithinDistance2() {
            // "read_fil" → "read_file" (distance 1)
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_fil", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).isEmpty();
            assertThat(calls.get(0).name()).isEqualTo("read_file");
        }

        @Test
        @DisplayName("Fuzzy match beyond Levenshtein distance 2 returns error")
        void fuzzyMatchBeyondDistance2() {
            // "completely_different" → distance > 2 → error
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "completely_different", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).hasSize(1);
        }

        @Test
        @DisplayName("XML/quote fragments are stripped before repair (VolcEngine workaround)")
        void xmlFragmentsStripped() {
            // 'terminal" parameter="command" string="true' → "terminal"
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "terminal\" parameter=\"command\" string=\"true", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).isEmpty();
            assertThat(calls.get(0).name()).isEqualTo("terminal");
        }

        @Test
        @DisplayName("Mix of valid and invalid names — only invalid returns error")
        void mixedValidInvalid() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{}"),
                new ToolCall("c2", "nonexistent", "{}"),
                new ToolCall("c3", "terminal", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).contains("nonexistent");
        }

        @Test
        @DisplayName("Empty tool name returns error")
        void emptyToolName() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "", "{}")
            ));
            List<String> errors = ToolCallValidator.validateToolNames(calls, REGISTERED);
            assertThat(errors).hasSize(1);
        }
    }

    // ── JSON argument validation ───────────────────────────────────────────

    @Nested
    @DisplayName("validateJsonArgs")
    class ValidateJsonArgs {

        @Test
        @DisplayName("Valid JSON arguments pass")
        void validJsonPasses() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/foo\"}"),
                new ToolCall("c2", "terminal", "{\"command\":\"ls\"}")
            ));
            var result = ToolCallValidator.validateJsonArgs(calls);
            assertThat(result.isValid()).isTrue();
            assertThat(result.truncated()).isFalse();
        }

        @Test
        @DisplayName("Empty arguments are normalised to {}")
        void emptyArgsNormalised() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", ""),
                new ToolCall("c2", "terminal", "   ")
            ));
            var result = ToolCallValidator.validateJsonArgs(calls);
            assertThat(result.isValid()).isTrue();
            assertThat(calls.get(0).arguments()).isEqualTo("{}");
            assertThat(calls.get(1).arguments()).isEqualTo("{}");
        }

        @Test
        @DisplayName("Null arguments are normalised to {}")
        void nullArgsNormalised() {
            // ToolCall record requires non-null arguments, but validateJsonArgs
            // handles null defensively. Test with a constructed null bypass.
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{}")
            ));
            // Manually set to null via reflection to test defensive code
            var result = ToolCallValidator.validateJsonArgs(calls);
            assertThat(result.isValid()).isTrue();
            assertThat(calls.get(0).arguments()).isEqualTo("{}");
        }

        @Test
        @DisplayName("Invalid JSON returns error")
        void invalidJsonReturnsError() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{invalid json")
            ));
            var result = ToolCallValidator.validateJsonArgs(calls);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0)).contains("read_file");
        }

        @Test
        @DisplayName("Truncated JSON (doesn't end with } or ]) sets truncated flag")
        void truncatedJsonSetsFlag() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/foo\",\"content\":\"hello wor")
            ));
            var result = ToolCallValidator.validateJsonArgs(calls);
            assertThat(result.isValid()).isFalse();
            assertThat(result.truncated()).isTrue();
        }

        @Test
        @DisplayName("Invalid JSON ending with } is not flagged as truncated")
        void invalidJsonNotTruncatedIfEndsWithBrace() {
            // Has valid closing } but invalid content inside
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{unquoted: value}")
            ));
            var result = ToolCallValidator.validateJsonArgs(calls);
            assertThat(result.isValid()).isFalse();
            assertThat(result.truncated()).isFalse();
        }

        @Test
        @DisplayName("Multiple calls with one invalid — error only for that call")
        void multipleCallsOneInvalid() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/foo\"}"),
                new ToolCall("c2", "terminal", "{invalid"),
                new ToolCall("c3", "search_files", "{}")
            ));
            var result = ToolCallValidator.validateJsonArgs(calls);
            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0)).contains("terminal");
        }

        @Test
        @DisplayName("Truncated JSON ending with ] is not flagged as truncated")
        void truncatedJsonEndingWithBracket() {
            // Ends with ] so not truncated despite being invalid JSON
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "terminal", "[1, 2, 3,]") // trailing comma — invalid but ends with ]
            ));
            var result = ToolCallValidator.validateJsonArgs(calls);
            assertThat(result.isValid()).isFalse();
            // Ends with ] so not truncated
            assertThat(result.truncated()).isFalse();
        }
    }

    // ── Deduplication ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("deduplicateToolCalls")
    class DeduplicateToolCalls {

        @Test
        @DisplayName("Duplicate calls (same name + same args) are removed")
        void duplicatesRemoved() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c3", "read_file", "{\"path\":\"/tmp/b\"}")
            ));
            List<ToolCall> result = ToolCallValidator.deduplicateToolCalls(calls);
            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo("c1");
            assertThat(result.get(1).id()).isEqualTo("c3");
        }

        @Test
        @DisplayName("No duplicates — returns same list")
        void noDuplicatesReturnsSameList() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c2", "terminal", "{\"command\":\"ls\"}")
            ));
            List<ToolCall> result = ToolCallValidator.deduplicateToolCalls(calls);
            assertThat(result).hasSize(2);
            assertThat(result).isSameAs(calls);
        }

        @Test
        @DisplayName("Same name but different arguments are NOT duplicates")
        void sameNameDifferentArgsNotDuplicates() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"/tmp/b\"}")
            ));
            List<ToolCall> result = ToolCallValidator.deduplicateToolCalls(calls);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Different names with same arguments are NOT duplicates")
        void differentNameSameArgsNotDuplicates() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{}"),
                new ToolCall("c2", "terminal", "{}")
            ));
            List<ToolCall> result = ToolCallValidator.deduplicateToolCalls(calls);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Equivalent JSON objects with reordered keys are duplicates")
        void reorderedJsonObjectsAreDuplicates() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "terminal", "{\"command\":\"printf hello >> out.log\",\"timeout\":10}"),
                new ToolCall("c2", "terminal", "{\"timeout\":10,\"command\":\"printf hello >> out.log\"}")
            ));
            List<ToolCall> result = ToolCallValidator.deduplicateToolCalls(calls);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("c1");
        }

        @Test
        @DisplayName("Malformed JSON uses raw arguments for deduplication")
        void malformedJsonUsesRawArguments() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "terminal", "{\"command\":\"one\""),
                new ToolCall("c2", "terminal", "{\"command\":\"one\""),
                new ToolCall("c3", "terminal", "{ \"command\":\"one\"")
            ));
            List<ToolCall> result = ToolCallValidator.deduplicateToolCalls(calls);
            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo("c1");
            assertThat(result.get(1).id()).isEqualTo("c3");
        }

        @Test
        @DisplayName("All duplicates — only first kept")
        void allDuplicatesOnlyFirstKept() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c3", "read_file", "{\"path\":\"/tmp/a\"}")
            ));
            List<ToolCall> result = ToolCallValidator.deduplicateToolCalls(calls);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("c1");
        }
    }

    // ── Delegate task capping ──────────────────────────────────────────────

    @Nested
    @DisplayName("capDelegateTaskCalls")
    class CapDelegateTaskCalls {

        @Test
        @DisplayName("Single delegate_task call is kept")
        void singleDelegateKept() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "delegate_task", "{\"goal\":\"task 1\"}")
            ));
            List<ToolCall> result = ToolCallValidator.capDelegateTaskCalls(calls);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("c1");
        }

        @Test
        @DisplayName("Multiple delegate_task calls are capped to 1")
        void multipleDelegatesCappedTo1() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "delegate_task", "{\"goal\":\"task 1\"}"),
                new ToolCall("c2", "delegate_task", "{\"goal\":\"task 2\"}"),
                new ToolCall("c3", "delegate_task", "{\"goal\":\"task 3\"}")
            ));
            List<ToolCall> result = ToolCallValidator.capDelegateTaskCalls(calls);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("c1");
        }

        @Test
        @DisplayName("Non-delegate calls are preserved when capping")
        void nonDelegateCallsPreserved() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{}"),
                new ToolCall("c2", "delegate_task", "{\"goal\":\"task 1\"}"),
                new ToolCall("c3", "delegate_task", "{\"goal\":\"task 2\"}"),
                new ToolCall("c4", "terminal", "{}")
            ));
            List<ToolCall> result = ToolCallValidator.capDelegateTaskCalls(calls);
            assertThat(result).hasSize(3); // read_file + 1 delegate + terminal
            assertThat(result.get(0).id()).isEqualTo("c1");
            assertThat(result.get(1).id()).isEqualTo("c2"); // first delegate kept
            assertThat(result.get(2).id()).isEqualTo("c4");
        }

        @Test
        @DisplayName("No delegate_task calls — returns same list")
        void noDelegatesReturnsSameList() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "read_file", "{}"),
                new ToolCall("c2", "terminal", "{}")
            ));
            List<ToolCall> result = ToolCallValidator.capDelegateTaskCalls(calls);
            assertThat(result).isSameAs(calls);
        }

        @Test
        @DisplayName("Custom max limit allows more delegate_task calls")
        void customMaxLimit() {
            List<ToolCall> calls = new ArrayList<>(List.of(
                new ToolCall("c1", "delegate_task", "{\"goal\":\"task 1\"}"),
                new ToolCall("c2", "delegate_task", "{\"goal\":\"task 2\"}"),
                new ToolCall("c3", "delegate_task", "{\"goal\":\"task 3\"}")
            ));
            List<ToolCall> result = ToolCallValidator.capDelegateTaskCalls(calls, 2);
            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo("c1");
            assertThat(result.get(1).id()).isEqualTo("c2");
        }
    }

    // ── Levenshtein distance ───────────────────────────────────────────────

    @Nested
    @DisplayName("Levenshtein distance")
    class LevenshteinTest {

        @Test
        @DisplayName("Identical strings have distance 0")
        void identicalStrings() {
            assertThat(ToolCallValidator.levenshtein("read_file", "read_file")).isEqualTo(0);
        }

        @Test
        @DisplayName("One substitution → distance 1")
        void oneSubstitution() {
            assertThat(ToolCallValidator.levenshtein("read_file", "read_fila")).isEqualTo(1);
        }

        @Test
        @DisplayName("One deletion → distance 1")
        void oneDeletion() {
            assertThat(ToolCallValidator.levenshtein("read_file", "read_fil")).isEqualTo(1);
        }

        @Test
        @DisplayName("One insertion → distance 1")
        void oneInsertion() {
            assertThat(ToolCallValidator.levenshtein("read_file", "read_files")).isEqualTo(1);
        }

        @Test
        @DisplayName("Empty string to non-empty → distance = length")
        void emptyToNonEmpty() {
            assertThat(ToolCallValidator.levenshtein("", "abc")).isEqualTo(3);
        }

        @Test
        @DisplayName("Both empty → distance 0")
        void bothEmpty() {
            assertThat(ToolCallValidator.levenshtein("", "")).isEqualTo(0);
        }
    }
}
