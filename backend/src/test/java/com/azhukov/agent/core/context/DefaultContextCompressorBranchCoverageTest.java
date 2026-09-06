package com.azhukov.agent.core.context;

import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.persistence.entity.CompressionLockEntity;
import com.azhukov.agent.core.ports.CompressionLockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage tests for DefaultContextCompressor — focuses on:
 * null/empty messages, lock tracking edge cases, tool output pruning,
 * summary extraction, fallback summarization, content length for budget.
 */
@ExtendWith(MockitoExtension.class)
class DefaultContextCompressorBranchCoverageTest {

    @Mock
    private com.azhukov.agent.core.ports.CompressionLockPort lockRepository;

    private AgentProperties properties;
    private DefaultContextCompressor compressor;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);
        compressor = new DefaultContextCompressor(new NoOpModelClient(), lockRepository, properties);
    }

    // ── Null/empty messages ──

    @Test
    void compressNullMessagesReturnsNull() {
        List<Message> result = compressor.compress(null, 1000);
        assertThat(result).isNull();
    }

    @Test
    void compressEmptyMessagesReturnsEmpty() {
        List<Message> result = compressor.compress(List.of(), 1000);
        assertThat(result).isEmpty();
    }

    @Test
    void compressUnderTargetReturnsSameMessages() {
        List<Message> messages = List.of(Message.user("hi"), Message.assistant("hello", 1));
        List<Message> result = compressor.compress(messages, 10000);
        assertThat(result).isSameAs(messages);
    }

    // ── Not enough messages to compress ──

    @Test
    void compressWithNotEnoughMessagesReturnsSame() {
        properties.getContext().setProtectFirstN(2);
        properties.getContext().setProtectLastN(2);
        // Total 3, protect 2+2=4 → not enough
        List<Message> messages = List.of(
            Message.user("a"), Message.assistant("b", 1), Message.user("c")
        );
        List<Message> result = compressor.compress(messages, 1);
        assertThat(result).isSameAs(messages);
    }

    // ── Middle messages empty ──

    @Test
    void compressWithEmptyMiddleReturnsSame() {
        properties.getContext().setProtectFirstN(2);
        properties.getContext().setProtectLastN(2);
        // Total 4, protect 2+2=4 → middle is empty
        List<Message> messages = List.of(
            Message.user("a"), Message.assistant("b", 1),
            Message.user("c"), Message.assistant("d", 2)
        );
        List<Message> result = compressor.compress(messages, 1);
        assertThat(result).isSameAs(messages);
    }

    // ── Tool output pruning ──

    @Test
    void compressPrunesLongToolOutput() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.complete(any(), any()))
            .thenReturn(ChatResponse.text("summary"));

        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);

        DefaultContextCompressor comp = new DefaultContextCompressor(modelClient, lockRepository, properties);

        String longToolOutput = "x".repeat(600);
        List<Message> messages = List.of(
            Message.user("task"),
            Message.toolResult("call-1", longToolOutput, 1),
            Message.user("filler-1 ".repeat(200)),
            Message.assistant("filler-2 ".repeat(200), 1),
            Message.assistant("done", 2)
        );

        List<Message> result = comp.compress(messages, 100);
        assertThat(result).hasSize(5); // head + summary + tail(3, Hermes floor)
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
    }

    // ── Tool call details in summary input ──

    @Test
    void compressIncludesToolCallDetailsInSummary() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.complete(any(), any()))
            .thenReturn(ChatResponse.text("summary with tool details"));

        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);

        DefaultContextCompressor comp = new DefaultContextCompressor(modelClient, lockRepository, properties);

        ToolCall tc = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}");
        List<Message> messages = List.of(
            Message.user("a".repeat(2000)),  // Large enough to exceed target
            Message.assistantToolCalls(List.of(tc), 1),
            Message.user("filler-1 ".repeat(200)),
            Message.assistant("filler-2 ".repeat(200), 1),
            Message.assistant("done", 2)
        );

        List<Message> result = comp.compress(messages, 100);
        // head(1) + summary(1) + tail(3, Hermes floor) = 5
        assertThat(result).hasSize(5);
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
    }

    @Test
    void compressIncludesToolCallWithLongArgsTruncatedInSummary() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.complete(any(), any()))
            .thenReturn(ChatResponse.text("summary"));

        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);

        DefaultContextCompressor comp = new DefaultContextCompressor(modelClient, lockRepository, properties);

        String longArgs = "{\"data\":\"" + "x".repeat(300) + "\"}";
        ToolCall tc = new ToolCall("call-1", "write_file", longArgs);
        List<Message> messages = List.of(
            Message.user("a".repeat(2000)),  // Large enough to exceed target
            Message.assistantToolCalls(List.of(tc), 1),
            Message.user("filler-1 ".repeat(200)),
            Message.assistant("filler-2 ".repeat(200), 1),
            Message.assistant("done", 2)
        );

        List<Message> result = comp.compress(messages, 100);
        assertThat(result).hasSize(5);
    }

    @Test
    void compressWithBlankContentToolCallIsSkipped() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.complete(any(), any()))
            .thenReturn(ChatResponse.text("summary"));

        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);

        DefaultContextCompressor comp = new DefaultContextCompressor(modelClient, lockRepository, properties);

        // Tool call with blank content but valid arguments
        ToolCall tc = new ToolCall("call-1", "write_file", "{\"path\":\"/tmp/test\"}");
        List<Message> messages = List.of(
            Message.user("a".repeat(2000)),  // Large enough to exceed target
            Message.assistantToolCalls(List.of(tc), 1),
            Message.user("filler-1 ".repeat(200)),
            Message.assistant("filler-2 ".repeat(200), 1),
            Message.assistant("done", 2)
        );

        List<Message> result = comp.compress(messages, 100);
        assertThat(result).hasSize(5);
    }

    @Test
    void compressConvertsSummaryTokenBudgetToCharacterLimit() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.complete(any(), any())).thenReturn(ChatResponse.text("s".repeat(2_500)));
        properties.getContext().setSummaryChunkTokens(1_000);
        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);
        DefaultContextCompressor comp = new DefaultContextCompressor(modelClient, lockRepository, properties);

        List<Message> result = comp.compress(List.of(
            Message.user("a".repeat(2_000)),
            Message.user("b".repeat(2_000)),
            Message.assistant("c".repeat(2_000), 1),
            Message.user("d".repeat(2_000)),
            Message.user("tail")
        ), 100);

        assertThat(result.get(1).content()).contains("s".repeat(2_500));
        assertThat(result.get(1).content()).hasSizeLessThanOrEqualTo(5_300);
    }

    // ── Previous summary extraction ──

    @Test
    void compressExtractsAndIncorporatesPreviousSummary() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.complete(any(), any()))
            .thenReturn(ChatResponse.text("updated summary"));

        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);

        DefaultContextCompressor comp = new DefaultContextCompressor(modelClient, lockRepository, properties);

        // Include a previous summary in the middle messages
        String previousSummary = "Earlier conversation (summarized):\nPrevious summary text here\n--- END OF CONTEXT SUMMARY — respond to the message below, not the summary above ---";
        List<Message> messages = List.of(
            Message.user("task"),
            Message.system(previousSummary),
            Message.user("filler-1 ".repeat(200)),
            Message.assistant("filler-2 ".repeat(200), 1),
            Message.assistant("done", 2)
        );

        List<Message> result = comp.compress(messages, 100);
        assertThat(result).hasSize(5);
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
    }

    // ── Fallback summarize when LLM fails ──

    @Test
    void compressFallsBackWhenLlmReturnsNullContent() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.complete(any(), any()))
            .thenReturn(ChatResponse.text(null));

        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);

        DefaultContextCompressor comp = new DefaultContextCompressor(modelClient, lockRepository, properties);

        List<Message> messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler-1 ".repeat(200)),
            Message.assistant("filler-2 ".repeat(200), 2),
            Message.user("current")
        );

        List<Message> result = comp.compress(messages, 100);
        assertThat(result).hasSize(5);
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(1).content()).contains("REFERENCE ONLY");
    }

    @Test
    void compressFallsBackWhenLlmReturnsBlankContent() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.complete(any(), any()))
            .thenReturn(ChatResponse.text("   "));

        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);

        DefaultContextCompressor comp = new DefaultContextCompressor(modelClient, lockRepository, properties);

        List<Message> messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler-1 ".repeat(200)),
            Message.assistant("filler-2 ".repeat(200), 2),
            Message.user("current")
        );

        List<Message> result = comp.compress(messages, 100);
        assertThat(result).hasSize(5);
    }

    @Test
    void compressFallsBackWhenLlmThrowsException() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.complete(any(), any()))
            .thenThrow(new RuntimeException("LLM unavailable"));

        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);

        DefaultContextCompressor comp = new DefaultContextCompressor(modelClient, lockRepository, properties);

        List<Message> messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler-1 ".repeat(200)),
            Message.assistant("filler-2 ".repeat(200), 2),
            Message.user("current")
        );

        List<Message> result = comp.compress(messages, 100);
        assertThat(result).hasSize(5);
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(1).content()).isNotBlank();
    }

    // ── Lock tracking ──

    @Test
    void isLockedNullSessionIdUsesAnonymousKey() {
        DefaultContextCompressor comp = new DefaultContextCompressor(new NoOpModelClient(), lockRepository, properties);
        assertThat(comp.isLocked(null, 1)).isFalse();
        comp.lock(null, 1);
        assertThat(comp.isLocked(null, 1)).isTrue();
    }

    @Test
    void isLockedWithValidSessionIdAndInMemoryLock() {
        DefaultContextCompressor comp = new DefaultContextCompressor(new NoOpModelClient(), lockRepository, properties);
        String sessionId = UUID.randomUUID().toString();
        assertThat(comp.isLocked(sessionId, 1)).isFalse();
        comp.lock(sessionId, 2);
        assertThat(comp.isLocked(sessionId, 1)).isTrue();
        assertThat(comp.isLocked(sessionId, 3)).isFalse();
    }

    @Test
    void isLockedWithDbRepositoryCheck() {
        String sessionId = UUID.randomUUID().toString();
        when(lockRepository.findBySessionId(UUID.fromString(sessionId)))
            .thenReturn(Optional.of(new CompressionLockEntity()));

        DefaultContextCompressor comp = new DefaultContextCompressor(new NoOpModelClient(), lockRepository, properties);
        // No in-memory lock, but DB has one
        assertThat(comp.isLocked(sessionId, 1)).isTrue();
    }

    @Test
    void isLockedWithInvalidUuidReturnsFalse() {
        DefaultContextCompressor comp = new DefaultContextCompressor(new NoOpModelClient(), lockRepository, properties);
        // Invalid UUID → IllegalArgumentException caught → false
        assertThat(comp.isLocked("not-a-uuid", 1)).isFalse();
    }

    @Test
    void isLockedWithRepositoryRuntimeExceptionReturnsFalse() {
        String sessionId = UUID.randomUUID().toString();
        when(lockRepository.findBySessionId(UUID.fromString(sessionId)))
            .thenThrow(new RuntimeException("DB failed"));

        DefaultContextCompressor comp = new DefaultContextCompressor(new NoOpModelClient(), lockRepository, properties);
        assertThat(comp.isLocked(sessionId, 1)).isFalse();
    }

    @Test
    void lockWithInvalidUuidDoesNotPersistToDb() {
        DefaultContextCompressor comp = new DefaultContextCompressor(new NoOpModelClient(), lockRepository, properties);
        // Invalid UUID → IllegalArgumentException caught, but in-memory lock is still set
        comp.lock("not-a-uuid", 1);
        // Verify lockRepository.save was never called
        verify(lockRepository, never()).save(any());
    }

    @Test
    void lockWithValidUuidPersistsToDb() {
        String sessionId = UUID.randomUUID().toString();
        when(lockRepository.findBySessionId(UUID.fromString(sessionId)))
            .thenReturn(Optional.empty());

        DefaultContextCompressor comp = new DefaultContextCompressor(new NoOpModelClient(), lockRepository, properties);
        comp.lock(sessionId, 1);
        verify(lockRepository).save(any(CompressionLockEntity.class));
    }

    @Test
    void lockWithRepositoryRuntimeExceptionDoesNotThrow() {
        String sessionId = UUID.randomUUID().toString();
        when(lockRepository.findBySessionId(UUID.fromString(sessionId)))
            .thenThrow(new RuntimeException("DB failed"));

        DefaultContextCompressor comp = new DefaultContextCompressor(new NoOpModelClient(), lockRepository, properties);
        // Should not throw — just log warning
        comp.lock(sessionId, 1);
        // In-memory lock should still be set
        assertThat(comp.isLocked(sessionId, 1)).isTrue();
    }

    @Test
    void lockWithExistingLockDoesNotCreateDuplicate() {
        String sessionId = UUID.randomUUID().toString();
        CompressionLockEntity existing = new CompressionLockEntity();
        when(lockRepository.findBySessionId(UUID.fromString(sessionId)))
            .thenReturn(Optional.of(existing));

        DefaultContextCompressor comp = new DefaultContextCompressor(new NoOpModelClient(), lockRepository, properties);
        comp.lock(sessionId, 1);
        verify(lockRepository, never()).save(any());
    }

    // ── contentLengthForBudget ──

    @Test
    void contentLengthForBudgetWithNullContentAndNullImageCount() {
        Message msg = new Message(Role.USER, null, null, null, null, null, null);
        int length = compressor.contentLengthForBudget(msg);
        assertThat(length).isZero();
    }

    @Test
    void contentLengthForBudgetWithImages() {
        Message msg = new Message(Role.USER, "text", null, null, null, null, 3);
        int length = compressor.contentLengthForBudget(msg);
        // text.length() + 3 * IMAGE_CHAR_EQUIVALENT
        assertThat(length).isEqualTo(4 + 3 * DefaultContextCompressor.IMAGE_CHAR_EQUIVALENT);
    }

    // ── logCompressionBoundary ──

    @Test
    void logCompressionBoundaryWithNullCallbackDoesNotThrow() {
        compressor.logCompressionBoundary("session-1", null);
        // Should not throw
    }

    @Test
    void logCompressionBoundaryInvokesCallback() {
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> captured = new java.util.concurrent.atomic.AtomicReference<>();
        compressor.logCompressionBoundary("session-1", captured::set);
        assertThat(captured.get()).isNotNull();
    }

    // ── Fallback summarize with many turns ──

    @Test
    void fallbackSummarizeWithExcessiveTurnsTruncatesWithOmittedMessage() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.complete(any(), any()))
            .thenThrow(new RuntimeException("always fail"));

        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);
        properties.getContext().setSummaryChunkTokens(200); // Small token budget to force truncation

        DefaultContextCompressor comp = new DefaultContextCompressor(modelClient, lockRepository, properties);

        // Create many middle messages to force truncation
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("first"));
        for (int i = 0; i < 20; i++) {
            messages.add(Message.assistant("turn " + i + " content", i + 1));
        }
        messages.add(Message.user("last"));

        List<Message> result = comp.compress(messages, 100);
        // Hermes tail floor: tail = max(3, min(1, 8)) = 3 → head(1)+summary(1)+tail(3) = 5
        assertThat(result).hasSize(5);
        // Summary uses the Hermes structured deterministic fallback format.
        String summary = result.get(1).content();
        assertThat(summary).contains("[CONTEXT COMPACTION — REFERENCE ONLY]");
        assertThat(summary).contains("## Historical Task Snapshot");
        assertThat(summary).contains("...[fallback summary truncated]");
        assertThat(summary).contains("--- END OF CONTEXT SUMMARY");
    }
}