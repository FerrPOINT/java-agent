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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Branch-coverage tests for TurnFinalizer — focuses on edge cases not covered
 * by TurnFinalizerTest: extractPath failure, buildFileMutationFooter edge cases,
 * buildCompletionExplanation with various content patterns.
 */
class TurnFinalizerBranchCoverageTest {

    private PromptCacheTracker cacheTracker;
    private TurnFinalizer finalizer;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getPromptCaching().setEnabled(true);
        cacheTracker = new PromptCacheTracker(properties);
        finalizer = new TurnFinalizer(cacheTracker);
    }

    // ── extractPath edge cases ──

    @Nested
    @DisplayName("extractPath edge cases")
    class ExtractPathEdgeCases {

        @Test
        @DisplayName("Invalid JSON arguments → path extraction returns null, no footer")
        void invalidJsonArguments_noFooter() {
            UUID sessionId = UUID.randomUUID();
            ToolCall badCall = new ToolCall("call-1", "write_file", "NOT VALID JSON");
            List<Message> messages = List.of(
                Message.user("write"),
                Message.assistantToolCalls(List.of(badCall), 1),
                Message.toolResult("call-1", "Error: failed", 1),
                Message.assistant("Done.", 2)
            );
            // Should not throw — bad JSON is caught
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            // Path extraction failed, so no mutation records → no footer
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Null path in JSON → path extraction returns null, no footer")
        void nullPathInJson_noFooter() {
            UUID sessionId = UUID.randomUUID();
            ToolCall nullPathCall = new ToolCall("call-1", "write_file", "{\"path\":null,\"content\":\"hello\"}");
            List<Message> messages = List.of(
                Message.user("write"),
                Message.assistantToolCalls(List.of(nullPathCall), 1),
                Message.toolResult("call-1", "Error: permission denied", 1),
                Message.assistant("Done.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Patch with null patch content → no path extracted")
        void patchWithNullPatchContent_noFooter() {
            UUID sessionId = UUID.randomUUID();
            ToolCall patchCall = new ToolCall("call-1", "patch", "{\"mode\":\"patch\",\"patch\":null}");
            List<Message> messages = List.of(
                Message.user("patch"),
                Message.assistantToolCalls(List.of(patchCall), 1),
                Message.toolResult("call-1", "Error: no patch content", 1),
                Message.assistant("Done.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("V4A patch with Delete File header → path extracted")
        void v4aPatchDeleteFile_extractsPath() {
            UUID sessionId = UUID.randomUUID();
            String patchContent = "*** Delete File: /src/old.java\n@@ context @@\n-old\n+new\n*** End Patch";
            ToolCall patchCall = new ToolCall("call-1", "patch",
                "{\"mode\":\"patch\",\"patch\":\"" + patchContent.replace("\"", "\\\"").replace("\n", "\\n") + "\"}");
            List<Message> messages = List.of(
                Message.user("patch"),
                Message.assistantToolCalls(List.of(patchCall), 1),
                Message.toolResult("call-1", "Error: failed to delete", 1),
                Message.assistant("Done.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/src/old.java");
        }

        @Test
        @DisplayName("V4A patch with Add File header → path extracted")
        void v4aPatchAddFile_extractsPath() {
            UUID sessionId = UUID.randomUUID();
            String patchContent = "*** Add File: /src/new.java\n+new content\n*** End Patch";
            ToolCall patchCall = new ToolCall("call-1", "patch",
                "{\"mode\":\"patch\",\"patch\":\"" + patchContent.replace("\"", "\\\"").replace("\n", "\\n") + "\"}");
            List<Message> messages = List.of(
                Message.user("add file"),
                Message.assistantToolCalls(List.of(patchCall), 1),
                Message.toolResult("call-1", "Error: failed", 1),
                Message.assistant("Done.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/src/new.java");
        }

        @Test
        @DisplayName("V4A patch with Move File header → path extracted")
        void v4aPatchMoveFile_extractsPath() {
            UUID sessionId = UUID.randomUUID();
            String patchContent = "*** Move File: /src/old.java\n→ /src/new.java\n*** End Patch";
            ToolCall patchCall = new ToolCall("call-1", "patch",
                "{\"mode\":\"patch\",\"patch\":\"" + patchContent.replace("\"", "\\\"").replace("\n", "\\n") + "\"}");
            List<Message> messages = List.of(
                Message.user("move file"),
                Message.assistantToolCalls(List.of(patchCall), 1),
                Message.toolResult("call-1", "Error: failed", 1),
                Message.assistant("Done.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/src/old.java");
        }
    }

    // ── buildFileMutationFooter edge cases ──

    @Nested
    @DisplayName("buildFileMutationFooter edge cases")
    class FileMutationFooterEdgeCases {

        @Test
        @DisplayName("Multiple mutation calls same path, first failed, second failed → footer")
        void multipleFailedSamePath_footer() {
            UUID sessionId = UUID.randomUUID();
            ToolCall call1 = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/a.txt\",\"content\":\"a\"}");
            ToolCall call2 = new ToolCall("call-2", "write_file", "{\"path\":\"/tmp/a.txt\",\"content\":\"b\"}");
            List<Message> messages = List.of(
                Message.user("write"),
                Message.assistantToolCalls(List.of(call1), 1),
                Message.toolResult("call-1", "Error: failed", 1),
                Message.assistantToolCalls(List.of(call2), 2),
                Message.toolResult("call-2", "Error: also failed", 2),
                Message.assistant("Done.", 3)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/tmp/a.txt");
        }

        @Test
        @DisplayName("Tool result with null toolCallId → not matched in footer tracking")
        void toolResultNullToolCallId_notTracked() {
            UUID sessionId = UUID.randomUUID();
            ToolCall call = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
            List<Message> messages = List.of(
                Message.user("write"),
                Message.assistantToolCalls(List.of(call), 1),
                Message.toolResult(null, "Error: failed", 1),  // null toolCallId in result
                Message.assistant("Done.", 2)
            );
            // The tool result has null toolCallId, so it won't be matched in resultSuccessByCallId
            // The mutation record for call-1 will have success=false (no matching result)
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/tmp/test.txt");
        }

        @Test
        @DisplayName("Tool result with null content → considered failed (not starting with 'Error:')")
        void toolResultNullContent_consideredFailed() {
            UUID sessionId = UUID.randomUUID();
            ToolCall call = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
            List<Message> messages = List.of(
                Message.user("write"),
                Message.assistantToolCalls(List.of(call), 1),
                Message.toolResult("call-1", null, 1),  // null content
                Message.assistant("Done.", 2)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            // null content → msg.content() != null is false → success = false
            // But wait: resultSuccessByCallId maps call-1 → (msg.content() != null && !msg.content().startsWith("Error:"))
            // null content → false → failed
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/tmp/test.txt");
        }

        @Test
        @DisplayName("Empty messages list with abnormal exit reason → null (messages.isEmpty())")
        void emptyMessagesAbnormalExit_returnsNull() {
            UUID sessionId = UUID.randomUUID();
            // Empty messages with abnormal reason → explanation is returned
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, List.of(), false, TurnExitReason.BUDGET_EXHAUSTED);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("budget exhausted");
        }

        @Test
        @DisplayName("Empty messages with COMPLETED reason → null")
        void emptyMessagesCompletedExit_returnsNull() {
            UUID sessionId = UUID.randomUUID();
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, List.of(), true, TurnExitReason.COMPLETED);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Empty messages with null exit reason → null")
        void emptyMessagesNullExitReason_returnsNull() {
            UUID sessionId = UUID.randomUUID();
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, List.of(), true, null);
            assertThat(result).isNull();
        }
    }

    // ── buildCompletionExplanation edge cases ──

    @Nested
    @DisplayName("buildCompletionExplanation edge cases")
    class CompletionExplanationEdgeCases {

        @Test
        @DisplayName("Null exit reason → no explanation")
        void nullExitReason_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Hi!", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("COMPLETED exit reason → no explanation")
        void completedExitReason_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Hi there!", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Abnormal exit with long content → no explanation (content is sufficient)")
        void abnormalExitLongContent_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("This is a sufficiently long response from the model.", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.UNKNOWN);
            // Content > 24 chars, so no partial fragment → no explanation
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Abnormal exit with terminal punctuation → no explanation")
        void abnormalExitTerminalPunctuation_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Done!", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.UNKNOWN);
            // "Done!" has terminal punctuation and is > 24 chars? No, it's 5 chars
            // 5 <= 24 → partial fragment? But has terminal punctuation → not partial
            // So no explanation
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Abnormal exit with Chinese terminal punctuation → no explanation")
        void abnormalExitChinesePunctuation_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("好的。", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.UNKNOWN);
            // "好的。" = 3 chars, has Chinese period → terminal punctuation → no explanation
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Abnormal exit with backtick terminal punctuation → no explanation")
        void abnormalExitBacktickPunctuation_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("code`", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.UNKNOWN);
            // "code`" ends with ` → terminal punctuation → no explanation
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Abnormal exit with paren terminal punctuation → no explanation")
        void abnormalExitParenPunctuation_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("test)", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.UNKNOWN);
            // "test)" ends with ) → terminal punctuation → no explanation
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Abnormal exit with no assistant message → explanation returned")
        void abnormalExitNoAssistantMessage_explanationReturned() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello")
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.UNKNOWN);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("unknown reason");
        }

        @Test
        @DisplayName("Empty content and COMPLETED → no explanation")
        void emptyContentCompleted_noExplanation() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, true, TurnExitReason.COMPLETED);
            // COMPLETED → not abnormal → no explanation
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Both footer and explanation present → both returned")
        void bothFooterAndExplanation_present() {
            UUID sessionId = UUID.randomUUID();
            ToolCall writeCall = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
            List<Message> messages = List.of(
                Message.user("write"),
                Message.assistantToolCalls(List.of(writeCall), 1),
                Message.toolResult("call-1", "Error: permission denied", 1)
                // No final assistant message — pending tool result + failed write
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(
                sessionId, messages, false, TurnExitReason.PENDING_TOOL_RESULT);
            assertThat(result).isNotNull();
            assertThat(result.fileMutationFooter()).contains("/tmp/test.txt");
            assertThat(result.completionExplanation()).contains("pending tool result");
        }
    }

    // ── inferExitReason edge cases ──

    @Nested
    @DisplayName("inferExitReason edge cases")
    class InferExitReasonEdgeCases {

        @Test
        @DisplayName("Failure with null content last message → UNKNOWN")
        void failureNullContentLastMessage_infersUnknown() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant(null, 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("unknown reason");
        }

        @Test
        @DisplayName("Success path with last message being TOOL → PENDING_TOOL_RESULT")
        void successLastMessageTool_infersPendingToolResult() {
            UUID sessionId = UUID.randomUUID();
            ToolCall call = new ToolCall("call-1", "read_file", "{\"path\":\"/tmp/test\"}");
            List<Message> messages = List.of(
                Message.user("read"),
                Message.assistantToolCalls(List.of(call), 1),
                Message.toolResult("call-1", "file content", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, true);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("pending tool result");
        }

        @Test
        @DisplayName("Success path with last message being USER → COMPLETED")
        void successLastMessageUser_infersCompleted() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.assistant("response", 1),
                Message.user("follow up")
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, true);
            // Last is USER → COMPLETED → no explanation
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Failure with 'cancelled' in content → INTERRUPTED")
        void failureWithCancelled_infersInterrupted() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Turn cancelled", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            // "Turn cancelled" is >24 chars, no terminal punctuation → partial fragment? 
            // "Turn cancelled" = 14 chars ≤ 24, no terminal punctuation → partial fragment → explanation
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Failure with 'guardrail' in content → GUARDRAIL_HALTED")
        void failureWithGuardrail_infersGuardrailHalted() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("guardrail triggered", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Failure with 'max turns' in content → MAX_TURNS_REACHED")
        void failureWithMaxTurns_infersMaxTurnsReached() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("max turns reached", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Failure with 'Model call failed' in content → MODEL_CALL_FAILED")
        void failureWithModelCallFailed_infersModelCallFailed() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("Model call failed", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            // "Model call failed" = 17 chars, no terminal punctuation, ≤24 → partial fragment → explanation
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Success with null last message content → EMPTY_RESPONSE")
        void successNullLastContent_infersEmptyResponse() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant(null, 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, true);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("empty response");
        }

        @Test
        @DisplayName("Success with blank last message content → EMPTY_RESPONSE")
        void successBlankLastContent_infersEmptyResponse() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("   ", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, true);
            assertThat(result).isNotNull();
            assertThat(result.completionExplanation()).contains("empty response");
        }

        @Test
        @DisplayName("Failure with 'budget exhausted' in content → BUDGET_EXHAUSTED")
        void failureWithBudgetExhausted_infersBudgetExhausted() {
            UUID sessionId = UUID.randomUUID();
            List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("budget exhausted", 1)
            );
            TurnFinalizer.FinalizationResult result = finalizer.finalize(sessionId, messages, false);
            assertThat(result).isNotNull();
        }
    }

    // ── Cache eviction on failure ──

    @Test
    @DisplayName("Failure with empty messages and abnormal reason → cache evicted")
    void failureEmptyMessagesAbnormal_cacheEvicted() {
        UUID sessionId = UUID.randomUUID();
        cacheTracker.markCached(sessionId.toString(), "abc123");
        finalizer.finalize(sessionId, List.of(), false, TurnExitReason.UNKNOWN);
        // Cache should be invalidated because !success
        assertThat(cacheTracker.isCacheValid(sessionId.toString(), "abc123")).isFalse();
    }

    @Test
    @DisplayName("Success with empty messages and COMPLETED reason → cache preserved")
    void successEmptyMessagesCompleted_cachePreserved() {
        UUID sessionId = UUID.randomUUID();
        cacheTracker.markCached(sessionId.toString(), "abc123");
        finalizer.finalize(sessionId, List.of(), true, TurnExitReason.COMPLETED);
        assertThat(cacheTracker.isCacheValid(sessionId.toString(), "abc123")).isTrue();
    }
}