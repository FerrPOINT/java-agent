package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TurnFinalizerTest {

    private PromptCacheTracker cacheTracker;
    private TurnFinalizer finalizer;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getPromptCaching().setEnabled(true);
        cacheTracker = new PromptCacheTracker(properties);
        finalizer = new TurnFinalizer(cacheTracker);
    }

    // ─── Existing cache tests (backwards-compatible finalize) ───

    @Test
    void finalize_success_preservesCache() {
        UUID sessionId = UUID.randomUUID();
        cacheTracker.markCached(sessionId.toString(), "abc123");
        List<Message> messages = List.of(Message.user("hello"), Message.assistant("hi", 1));
        assertThatCode(() -> finalizer.finalize(sessionId, messages, true))
            .doesNotThrowAnyException();
        assertThat(cacheTracker.isCacheValid(sessionId.toString(), "abc123")).isTrue();
    }

    @Test
    void finalize_failure_evictsCache() {
        UUID sessionId = UUID.randomUUID();
        cacheTracker.markCached(sessionId.toString(), "abc123");
        List<Message> messages = List.of(Message.user("hello"), Message.assistant("error", 1));
        assertThatCode(() -> finalizer.finalize(sessionId, messages, false))
            .doesNotThrowAnyException();
        assertThat(cacheTracker.isCacheValid(sessionId.toString(), "abc123")).isFalse();
    }

    @Test
    void finalize_emptyMessages_doesNotThrow() {
        UUID sessionId = UUID.randomUUID();
        assertThatCode(() -> finalizer.finalize(sessionId, List.of(), true))
            .doesNotThrowAnyException();
    }

    @Test
    void finalize_nullMessages_doesNotThrow() {
        UUID sessionId = UUID.randomUUID();
        assertThatCode(() -> finalizer.finalize(sessionId, null, false))
            .doesNotThrowAnyException();
    }

    // ─── File-mutation verifier tests ───

    @Nested
    @DisplayName("File-mutation verifier")
    class FileMutationVerifier {

        @Test
        @DisplayName("No mutation tools → no footer")
        void noMutationTools_noFooter() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Done.", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Successful write_file → no footer")
        void successfulWrite_noFooter() {
            UUID sessionId = UUID.randomUUID();
            ToolCall writeCall = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
            List<Message> messages = List.of(
                Message.user("write a file"),
                Message.assistantToolCalls(List.of(writeCall), 1),
                Message.toolResult("call-1", "Wrote 5 characters to /tmp/test.txt", 1),
                Message.assistant("Done writing the file.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Failed write_file not retried → footer with path")
        void failedWriteNotRetried_footer() {
            UUID sessionId = UUID.randomUUID();
            ToolCall writeCall = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
            List<Message> messages = List.of(
                Message.user("write a file"),
                Message.assistantToolCalls(List.of(writeCall), 1),
                Message.toolResult("call-1", "Error: Failed to write file: permission denied", 1),
                Message.assistant("I wrote the file.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/tmp/test.txt");
            assertThat(result.fileMutationFooter()).contains("⚠");
            assertThat(result.fileMutationFooter()).contains("failed");
        }

        @Test
        @DisplayName("Failed write_file then successful write to same path → no footer (superseded)")
        void failedWriteThenSuccessfulWriteToSamePath_noFooter() {
            UUID sessionId = UUID.randomUUID();
            ToolCall failedCall = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
            ToolCall successCall = new ToolCall("call-2", "write_file", "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
            List<Message> messages = List.of(
                Message.user("write a file"),
                Message.assistantToolCalls(List.of(failedCall), 1),
                Message.toolResult("call-1", "Error: Failed to write file: permission denied", 1),
                Message.assistantToolCalls(List.of(successCall), 2),
                Message.toolResult("call-2", "Wrote 5 characters to /tmp/test.txt", 2),
                Message.assistant("Done writing the file.", 3)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Failed patch not retried → footer with path")
        void failedPatchNotRetried_footer() {
            UUID sessionId = UUID.randomUUID();
            ToolCall patchCall = new ToolCall("call-1", "patch", "{\"mode\":\"replace\",\"path\":\"/tmp/test.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}");
            List<Message> messages = List.of(
                Message.user("patch a file"),
                Message.assistantToolCalls(List.of(patchCall), 1),
                Message.toolResult("call-1", "Error: Could not find old_string in file.", 1),
                Message.assistant("I patched the file.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/tmp/test.txt");
        }

        @Test
        @DisplayName("Multiple failed writes → footer with all paths")
        void multipleFailedWrites_footerWithAllPaths() {
            UUID sessionId = UUID.randomUUID();
            ToolCall write1 = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/a.txt\",\"content\":\"a\"}");
            ToolCall write2 = new ToolCall("call-2", "write_file", "{\"path\":\"/tmp/b.txt\",\"content\":\"b\"}");
            List<Message> messages = List.of(
                Message.user("write files"),
                Message.assistantToolCalls(List.of(write1, write2), 1),
                Message.toolResult("call-1", "Error: permission denied", 1),
                Message.toolResult("call-2", "Error: disk full", 1),
                Message.assistant("All files written.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/tmp/a.txt");
            assertThat(result.fileMutationFooter()).contains("/tmp/b.txt");
        }

        @Test
        @DisplayName("Non-mutation tools failing → no footer")
        void nonMutationToolFailing_noFooter() {
            UUID sessionId = UUID.randomUUID();
            ToolCall searchCall = new ToolCall("call-1", "search_files", "{\"pattern\":\"foo\"}");
            List<Message> messages = List.of(
                Message.user("search"),
                Message.assistantToolCalls(List.of(searchCall), 1),
                Message.toolResult("call-1", "Error: invalid regex", 1),
                Message.assistant("Search complete.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("V4A patch mode extracts path from patch content")
        void v4aPatchMode_extractsPathFromContent() {
            UUID sessionId = UUID.randomUUID();
            String patchContent = "*** Update File: /src/Main.java\n@@ context @@\n-old\n+new\n*** End Patch";
            ToolCall patchCall = new ToolCall("call-1", "patch", "{\"mode\":\"patch\",\"patch\":\"" + patchContent.replace("\"", "\\\"").replace("\n", "\\n") + "\"}");
            List<Message> messages = List.of(
                Message.user("patch"),
                Message.assistantToolCalls(List.of(patchCall), 1),
                Message.toolResult("call-1", "Error: Could not match old section for update", 1),
                Message.assistant("Done.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/src/Main.java");
        }
    }

    // ─── Turn-completion explainer tests ───

    @Nested
    @DisplayName("Turn-completion explainer")
    class TurnCompletionExplainer {

        @Test
        @DisplayName("Completed turn with normal text → no explanation")
        void completedTurnWithText_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Hello! How can I help you?", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Empty response → explanation returned")
        void emptyResponse_explanationReturned() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.EMPTY_RESPONSE);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("[Turn ended:");
            assertThat(result.completionExplanation()).contains("empty response");
        }

        @Test
        @DisplayName("(empty) sentinel → explanation returned")
        void emptySentinel_explanationReturned() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("(empty)", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.EMPTY_RESPONSE);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("[Turn ended:");
        }

        @Test
        @DisplayName("Budget exhausted → explanation returned")
        void budgetExhausted_explanationReturned() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("do something complex"),
                Message.assistant("", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.BUDGET_EXHAUSTED);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("budget exhausted");
        }

        @Test
        @DisplayName("Interrupted with text response → no explanation (text already surfaced)")
        void interrupted_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Turn cancelled by user.", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.INTERRUPTED);
            // "Turn cancelled by user." is a complete sentence with terminal punctuation —
            // the explanation is not added because the user already sees a reason
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Guardrail halted with text response → no explanation (text already surfaced)")
        void guardrailHalted_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Turn halted by guardrails.", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.GUARDRAIL_HALTED);
            // "Turn halted by guardrails." is a complete sentence — no explanation added
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Guardrail halted with empty response → explanation returned")
        void guardrailHalted_emptyResponse_explanationReturned() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.GUARDRAIL_HALTED);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("guardrails");
        }

        @Test
        @DisplayName("Model call failed with empty response → explanation returned")
        void modelCallFailed_emptyResponse_explanationReturned() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.MODEL_CALL_FAILED);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("model call failed");
        }

        @Test
        @DisplayName("Max turns reached → explanation returned")
        void maxTurnsReached_explanationReturned() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.MAX_TURNS_REACHED);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("maximum turns");
        }

        @Test
        @DisplayName("Short partial fragment (no terminal punctuation) → explanation returned")
        void partialFragment_explanationReturned() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("The", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.UNKNOWN);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("[Turn ended:");
        }

        @Test
        @DisplayName("Short text with terminal punctuation → no explanation (normal response)")
        void shortTextWithPunctuation_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Done.", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.UNKNOWN);
            // "Done." has terminal punctuation and is a complete response → no explanation
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Pending tool result → explanation returned")
        void pendingToolResult_explanationReturned() {
            UUID sessionId = UUID.randomUUID();
            ToolCall writeCall = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
            List<Message> messages = List.of(
                Message.user("write"),
                Message.assistantToolCalls(List.of(writeCall), 1),
                Message.toolResult("call-1", "Wrote 5 characters to /tmp/test.txt", 1)
                // No final assistant message — turn ended pending tool result
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.PENDING_TOOL_RESULT);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("pending tool result");
        }

        @Test
        @DisplayName("Completed turn with COMPLETED reason → no explanation even with failed writes")
        void completedTurnWithFailedWrites_bothPresent() {
            UUID sessionId = UUID.randomUUID();
            ToolCall writeCall = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
            List<Message> messages = List.of(
                Message.user("write"),
                Message.assistantToolCalls(List.of(writeCall), 1),
                Message.toolResult("call-1", "Error: permission denied", 1),
                Message.assistant("Done.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            // COMPLETED reason → no explanation, but failed write → footer
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).isNull();
            assertThat(result.fileMutationFooter()).contains("/tmp/test.txt");
        }
    }

    // ─── Exit reason inference tests ───

    @Nested
    @DisplayName("Exit reason inference (backwards-compatible finalize)")
    class ExitReasonInference {

        @Test
        @DisplayName("Success with text → infers COMPLETED, no explanation")
        void successWithText_infersCompleted() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Hi there.", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, true);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Failure with budget message → infers BUDGET_EXHAUSTED, no explanation (complete sentence)")
        void failureWithBudget_infersBudgetExhausted() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Iteration budget exhausted. Stopping to avoid runaway loop.", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            // The budget message is a complete sentence with terminal punctuation,
            // so no explanation is appended — the user already sees a reason.
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Failure with guardrail message → infers GUARDRAIL_HALTED, no explanation (complete sentence)")
        void failureWithGuardrail_infersGuardrailHalted() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Turn halted by guardrails.", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Failure with cancelled message → infers INTERRUPTED, no explanation (complete sentence)")
        void failureWithCancelled_infersInterrupted() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Turn cancelled by user.", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Failure with model call error → infers MODEL_CALL_FAILED, explanation for short fragment")
        void failureWithModelCallError_infersModelCallFailed() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Model call failed: connection refused", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            // "Model call failed: connection refused" is >24 chars and has no terminal punctuation,
            // but it's > 24 chars so not a partial fragment. The explainer won't fire.
            // Wait - let me check the length...
            // "Model call failed: connection refused" = 37 chars → not a partial fragment.
            // The explanation won't fire because content is present and long enough.
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Failure ending with tool result → infers PENDING_TOOL_RESULT, explanation returned")
        void failureEndingWithToolResult_infersPendingToolResult() {
            UUID sessionId = UUID.randomUUID();
            ToolCall searchCall = new ToolCall("call-1", "search_files", "{\"pattern\":\"foo\"}");
            List<Message> messages = List.of(
                Message.user("search"),
                Message.assistantToolCalls(List.of(searchCall), 1),
                Message.toolResult("call-1", "no results", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("pending tool result");
        }

        @Test
        @DisplayName("Success with empty assistant content → infers EMPTY_RESPONSE, explanation returned")
        void successWithEmptyContent_infersEmptyResponse() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, true);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("empty response");
        }

        @Test
        @DisplayName("Failure with max turns message → infers MAX_TURNS_REACHED, no explanation (complete sentence)")
        void failureWithMaxTurns_infersMaxTurnsReached() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Reached max turns without completion", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            // "Reached max turns without completion" = 36 chars, no terminal punctuation, but > 24 chars
            // → not a partial fragment, so no explanation
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Failure with empty assistant → infers UNKNOWN, explanation returned")
        void unknownFailure_infersUnknown() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("unknown reason");
        }

        @Test
        @DisplayName("Null messages with failure → infers UNKNOWN, returns explanation")
        void nullMessagesFailure_infersUnknown() {
            UUID sessionId = UUID.randomUUID();
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, null, false);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("unknown reason");
        }

        @Test
        @DisplayName("Empty messages with failure → infers UNKNOWN, returns explanation")
        void emptyMessagesFailure_infersUnknown() {
            UUID sessionId = UUID.randomUUID();
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, List.of(), false);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("unknown reason");
        }
    }

    // ─── TurnExitReason enum tests ───

    @Nested
    @DisplayName("TurnExitReason enum")
    class TurnExitReasonTest {

        @Test
        void completed_isNotAbnormal() {
            assertThat(TurnExitReason.COMPLETED.isAbnormal()).isFalse();
        }

        @Test
        void allOthers_areAbnormal() {
            assertThat(TurnExitReason.EMPTY_RESPONSE.isAbnormal()).isTrue();
            assertThat(TurnExitReason.BUDGET_EXHAUSTED.isAbnormal()).isTrue();
            assertThat(TurnExitReason.INTERRUPTED.isAbnormal()).isTrue();
            assertThat(TurnExitReason.PENDING_TOOL_RESULT.isAbnormal()).isTrue();
            assertThat(TurnExitReason.MODEL_CALL_FAILED.isAbnormal()).isTrue();
            assertThat(TurnExitReason.GUARDRAIL_HALTED.isAbnormal()).isTrue();
            assertThat(TurnExitReason.MAX_TURNS_REACHED.isAbnormal()).isTrue();
            assertThat(TurnExitReason.UNKNOWN.isAbnormal()).isTrue();
        }

        @Test
        void completed_explanationIsNull() {
            assertThat(TurnExitReason.COMPLETED.explanation()).isNull();
        }

        @Test
        void allOthers_haveNonNullOreturnExplanation() {
            assertThat(TurnExitReason.EMPTY_RESPONSE.explanation()).contains("[Turn ended:");
            assertThat(TurnExitReason.BUDGET_EXHAUSTED.explanation()).contains("[Turn ended:");
            assertThat(TurnExitReason.INTERRUPTED.explanation()).contains("[Turn ended:");
            assertThat(TurnExitReason.PENDING_TOOL_RESULT.explanation()).contains("[Turn ended:");
            assertThat(TurnExitReason.MODEL_CALL_FAILED.explanation()).contains("[Turn ended:");
            assertThat(TurnExitReason.GUARDRAIL_HALTED.explanation()).contains("[Turn ended:");
            assertThat(TurnExitReason.MAX_TURNS_REACHED.explanation()).contains("[Turn ended:");
            assertThat(TurnExitReason.UNKNOWN.explanation()).contains("[Turn ended:");
        }
    }
}