package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.persistence.repository.CompressionLockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the two quick-win gaps:
 * <ul>
 *   <li>Gap 4 — Compression lock fail-open guard: if the lock subsystem throws, proceed without the lock.</li>
 *   <li>Gap 5 — MEDIA: directive stripping from summarizer input.</li>
 * </ul>
 */
class DefaultContextCompressorQuickWinsTest {

    private AgentProperties defaultProps() {
        AgentProperties props = new AgentProperties();
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        props.getContext().setMaxTokens(16000);
        return props;
    }

    // ─── Gap 4: Lock fail-open guard ───

    @Nested
    @DisplayName("Lock fail-open guard")
    class LockFailOpen {

        @Test
        @DisplayName("isLocked returns false when the lock repository throws")
        void isLockedFailsOpenOnRepositoryException() {
            CompressionLockRepository repo = mock(CompressionLockRepository.class);
            when(repo.findBySessionId(any(UUID.class)))
                .thenThrow(new RuntimeException("DB down"));

            DefaultContextCompressor compressor =
                new DefaultContextCompressor(mock(ModelClient.class), repo, defaultProps());

            // In-memory lock has not been set, so it falls through to the repository.
            // Repository throws → fail-open → false.
            assertThat(compressor.isLocked(UUID.randomUUID().toString(), 1)).isFalse();
        }

        @Test
        @DisplayName("lock does not throw when the lock repository throws; in-memory lock still set")
        void lockFailsOpenOnRepositoryException() {
            CompressionLockRepository repo = mock(CompressionLockRepository.class);
            when(repo.findBySessionId(any(UUID.class)))
                .thenThrow(new RuntimeException("DB down"));

            String sessionId = UUID.randomUUID().toString();
            DefaultContextCompressor compressor =
                new DefaultContextCompressor(mock(ModelClient.class), repo, defaultProps());

            // Should not throw — in-memory lock still set, DB failure is logged.
            compressor.lock(sessionId, 1);

            // In-memory lock was set, so isLocked returns true without touching the repo.
            assertThat(compressor.isLocked(sessionId, 1)).isTrue();
        }

        @Test
        @DisplayName("Compression proceeds even when lock repository throws during isLocked check")
        void compressionProceedsWhenLockRepoThrows() {
            CompressionLockRepository repo = mock(CompressionLockRepository.class);
            when(repo.findBySessionId(any(UUID.class)))
                .thenThrow(new RuntimeException("DB down"));

            ModelClient model = mock(ModelClient.class);
            when(model.complete(any(), any())).thenReturn(ChatResponse.text("summary"));

            DefaultContextCompressor compressor =
                new DefaultContextCompressor(model, repo, defaultProps());

            // isLocked fails open, lock fails open, compression still works.
            assertThat(compressor.isLocked(UUID.randomUUID().toString(), 1)).isFalse();

            List<Message> messages = List.of(
                Message.user("a".repeat(2000)),
                Message.assistant("b".repeat(2000), 1),
                Message.user("filler q ".repeat(100)),
                Message.assistant("filler a ".repeat(100), 2),
                Message.user("current")
            );
            List<Message> result = compressor.compress(messages, 100);
            assertThat(result).hasSize(5);
            assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
        }

        @Test
        @DisplayName("isLocked still returns true when in-memory lock is set, even if repo is broken")
        void isLockedUsesInMemoryLockBeforeRepo() {
            CompressionLockRepository repo = mock(CompressionLockRepository.class);
            when(repo.findBySessionId(any(UUID.class)))
                .thenThrow(new RuntimeException("DB down"));

            String sessionId = UUID.randomUUID().toString();
            DefaultContextCompressor compressor =
                new DefaultContextCompressor(mock(ModelClient.class), repo, defaultProps());

            compressor.lock(sessionId, 2);
            // In-memory lock at gen 2 → gen 1 is locked, gen 3 is not
            assertThat(compressor.isLocked(sessionId, 1)).isTrue();
            assertThat(compressor.isLocked(sessionId, 3)).isFalse();
        }
    }

    // ─── Gap 5: MEDIA: directive stripping ───

    @Nested
    @DisplayName("MEDIA: directive stripping")
    class MediaDirectiveStripping {

        @Test
        @DisplayName("MEDIA:/path directives are stripped from summarizer input")
        void mediaDirectivesStrippedFromSummarizerInput() {
            ModelClient model = mock(ModelClient.class);
            when(model.complete(any(), any())).thenAnswer(inv -> {
                List<Message> input = inv.getArgument(0);
                Message userMsg = input.get(1);
                String promptContent = userMsg.content();
                // MEDIA: directives should have been stripped before reaching the summarizer.
                assertThat(promptContent).doesNotContain("MEDIA:");
                assertThat(promptContent).doesNotContain("/tmp/screenshot.png");
                // But the surrounding text should still be there.
                assertThat(promptContent).contains("Here is a response");
                return ChatResponse.text("summary");
            });

            DefaultContextCompressor compressor =
                new DefaultContextCompressor(model, null, defaultProps());

            List<Message> messages = List.of(
                Message.user("a".repeat(2000)),
                Message.assistant("Here is a response MEDIA:/tmp/screenshot.png end", 1),
                Message.user("filler q ".repeat(100)),
                Message.assistant("filler a ".repeat(100), 2),
                Message.user("current")
            );

            List<Message> result = compressor.compress(messages, 100);
            assertThat(result).hasSize(5);
            assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
        }

        @Test
        @DisplayName("Multiple MEDIA: directives in a single message are all stripped")
        void multipleMediaDirectivesStripped() {
            ModelClient model = mock(ModelClient.class);
            when(model.complete(any(), any())).thenAnswer(inv -> {
                List<Message> input = inv.getArgument(0);
                Message userMsg = input.get(1);
                String promptContent = userMsg.content();
                assertThat(promptContent).doesNotContain("MEDIA:");
                assertThat(promptContent).contains("before");
                assertThat(promptContent).contains("between");
                assertThat(promptContent).contains("after");
                return ChatResponse.text("summary");
            });

            DefaultContextCompressor compressor =
                new DefaultContextCompressor(model, null, defaultProps());

            List<Message> messages = List.of(
                Message.user("a".repeat(2000)),
                Message.assistant(
                    "before MEDIA:/img1.png between MEDIA:/img2.png after", 1),
                Message.user("current")
            );

            List<Message> result = compressor.compress(messages, 100);
            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("MEDIA: directive stripping does not affect messages without directives")
        void noMediaDirectivesUnchanged() {
            ModelClient model = mock(ModelClient.class);
            when(model.complete(any(), any())).thenAnswer(inv -> {
                List<Message> input = inv.getArgument(0);
                Message userMsg = input.get(1);
                String promptContent = userMsg.content();
                assertThat(promptContent).contains("Normal text without directives");
                return ChatResponse.text("summary");
            });

            DefaultContextCompressor compressor =
                new DefaultContextCompressor(model, null, defaultProps());

            List<Message> messages = List.of(
                Message.user("a".repeat(2000)),
                Message.assistant("Normal text without directives", 1),
                Message.user("current")
            );

            List<Message> result = compressor.compress(messages, 100);
            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("MEDIA: directive with trailing whitespace boundary is handled")
        void mediaDirectiveWithWhitespaceBoundary() {
            ModelClient model = mock(ModelClient.class);
            when(model.complete(any(), any())).thenAnswer(inv -> {
                List<Message> input = inv.getArgument(0);
                Message userMsg = input.get(1);
                String promptContent = userMsg.content();
                // The MEDIA: directive followed by newline should be stripped
                assertThat(promptContent).doesNotContain("MEDIA:");
                return ChatResponse.text("summary");
            });

            DefaultContextCompressor compressor =
                new DefaultContextCompressor(model, null, defaultProps());

            List<Message> messages = List.of(
                Message.user("a".repeat(2000)),
                Message.assistant("Line one\nMEDIA:/tmp/file.png\nLine three", 1),
                Message.user("current")
            );

            List<Message> result = compressor.compress(messages, 100);
            assertThat(result).hasSize(3);
        }
    }
}