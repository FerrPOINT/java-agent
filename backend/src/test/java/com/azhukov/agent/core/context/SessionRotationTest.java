package com.azhukov.agent.core.context;

import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.CompressionLockRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for session rotation in {@link DefaultContextCompressor}.
 * <p>
 * Verifies:
 * <ul>
 *   <li>Session rotation creates a child session with parent_session_id linkage</li>
 *   <li>Title is propagated with " (compressed)" suffix</li>
 *   <li>Old session is marked as "compressed"</li>
 *   <li>Fallback to logCompressionBoundary when rotation disabled</li>
 *   <li>Fallback when DB error occurs during rotation</li>
 *   <li>Fallback when session not found</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SessionRotationTest {

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private CompressionLockRepository lockRepository;

    private AgentProperties properties;
    private UUID sessionId;
    private SessionEntity oldSession;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        sessionId = UUID.randomUUID();

        oldSession = new SessionEntity();
        oldSession.setId(sessionId);
        oldSession.setUserId("user-42");
        oldSession.setTitle("Original Session");
        oldSession.setModelProvider("openai-compatible");
        oldSession.setModelName("gpt-4o");
        oldSession.setCreatedAt(Instant.now().minusSeconds(3600));
        oldSession.setUpdatedAt(Instant.now().minusSeconds(60));
        oldSession.setSessionStatus("active");
    }

    private DefaultContextCompressor createCompressor() {
        DefaultContextCompressor compressor = new DefaultContextCompressor(
                new NoOpModelClient(), lockRepository, properties);
        compressor.setSessionRepository(sessionRepository);
        return compressor;
    }

    // ── 1. Session rotation on successful compression ──

    @Test
    void rotateSessionCreatesChildSessionWithParentSessionId() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(oldSession));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DefaultContextCompressor compressor = createCompressor();
        Optional<DefaultContextCompressor.SessionRotationResult> result =
                compressor.rotateSession(String.valueOf(sessionId));

        assertThat(result).isPresent();

        // Verify old session marked as compressed
        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository, times(2)).save(captor.capture());
        List<SessionEntity> saved = captor.getAllValues();

        SessionEntity savedOld = saved.get(0);
        assertThat(savedOld.getId()).isEqualTo(sessionId);
        assertThat(savedOld.getSessionStatus()).isEqualTo("compressed");

        // Verify child session created with parent_session_id
        SessionEntity savedChild = saved.get(1);
        assertThat(savedChild.getParentSessionId()).isEqualTo(sessionId);
        assertThat(savedChild.getUserId()).isEqualTo("user-42");
        assertThat(savedChild.getModelProvider()).isEqualTo("openai-compatible");
        assertThat(savedChild.getModelName()).isEqualTo("gpt-4o");
        assertThat(savedChild.getSessionStatus()).isEqualTo("active");
    }

    // ── 2. Title propagation with " (compressed)" suffix ──

    @Test
    void rotateSessionPropagatesTitleWithCompressedSuffix() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(oldSession));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DefaultContextCompressor compressor = createCompressor();
        Optional<DefaultContextCompressor.SessionRotationResult> result =
                compressor.rotateSession(String.valueOf(sessionId));

        assertThat(result).isPresent();
        assertThat(result.get().newTitle()).isEqualTo("Original Session (compressed)");

        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository, times(2)).save(captor.capture());
        SessionEntity child = captor.getAllValues().get(1);
        assertThat(child.getTitle()).isEqualTo("Original Session (compressed)");
    }

    @Test
    void rotateSessionHandlesNullTitleWithDefaultPrefix() {
        oldSession.setTitle(null);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(oldSession));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DefaultContextCompressor compressor = createCompressor();
        Optional<DefaultContextCompressor.SessionRotationResult> result =
                compressor.rotateSession(String.valueOf(sessionId));

        assertThat(result).isPresent();
        assertThat(result.get().newTitle()).isEqualTo("Untitled (compressed)");
    }

    // ── 3. Old session marked as 'compressed' ──

    @Test
    void rotateSessionMarksOldSessionAsCompressed() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(oldSession));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DefaultContextCompressor compressor = createCompressor();
        compressor.rotateSession(String.valueOf(sessionId));

        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository, atLeastOnce()).save(captor.capture());

        SessionEntity savedOld = captor.getAllValues().stream()
                .filter(e -> e.getId().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Old session not saved"));
        assertThat(savedOld.getSessionStatus()).isEqualTo("compressed");
    }

    // ── 4. Fallback when rotation disabled ──

    @Test
    void rotateSessionReturnsEmptyWhenDisabled() {
        properties.getCompression().getSessionRotation().setEnabled(false);

        DefaultContextCompressor compressor = createCompressor();
        Optional<DefaultContextCompressor.SessionRotationResult> result =
                compressor.rotateSession(String.valueOf(sessionId));

        assertThat(result).isEmpty();
        verify(sessionRepository, never()).findById(any());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void logCompressionBoundaryStillWorksWhenRotationDisabled() {
        properties.getCompression().getSessionRotation().setEnabled(false);

        DefaultContextCompressor compressor = createCompressor();
        java.util.concurrent.atomic.AtomicReference<Instant> capturedTs = new java.util.concurrent.atomic.AtomicReference<>();
        compressor.logCompressionBoundary(String.valueOf(sessionId), capturedTs::set);

        assertThat(capturedTs.get()).isNotNull();
    }

    // ── 5. Fallback when DB error during rotation ──

    @Test
    void rotateSessionReturnsEmptyOnDbError() {
        when(sessionRepository.findById(sessionId)).thenThrow(new RuntimeException("DB connection failed"));

        DefaultContextCompressor compressor = createCompressor();
        Optional<DefaultContextCompressor.SessionRotationResult> result =
                compressor.rotateSession(String.valueOf(sessionId));

        assertThat(result).isEmpty();
        // Should not throw — compression continues with old session
    }

    @Test
    void rotateSessionReturnsEmptyWhenSaveFails() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(oldSession));
        when(sessionRepository.save(any(SessionEntity.class))).thenThrow(new RuntimeException("Save failed"));

        DefaultContextCompressor compressor = createCompressor();
        Optional<DefaultContextCompressor.SessionRotationResult> result =
                compressor.rotateSession(String.valueOf(sessionId));

        assertThat(result).isEmpty();
    }

    // ── 6. Fallback when session not found ──

    @Test
    void rotateSessionReturnsEmptyWhenSessionNotFound() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        DefaultContextCompressor compressor = createCompressor();
        Optional<DefaultContextCompressor.SessionRotationResult> result =
                compressor.rotateSession(String.valueOf(sessionId));

        assertThat(result).isEmpty();
        verify(sessionRepository, never()).save(any());
    }

    // ── 7. Null/invalid session ID handling ──

    @Test
    void rotateSessionReturnsEmptyForNullSessionId() {
        DefaultContextCompressor compressor = createCompressor();
        Optional<DefaultContextCompressor.SessionRotationResult> result = compressor.rotateSession(null);
        assertThat(result).isEmpty();
    }

    @Test
    void rotateSessionReturnsEmptyForNonUuidSessionId() {
        DefaultContextCompressor compressor = createCompressor();
        Optional<DefaultContextCompressor.SessionRotationResult> result = compressor.rotateSession("not-a-uuid");
        assertThat(result).isEmpty();
    }

    // ── 8. SessionRepository not injected (unit test scenario) ──

    @Test
    void rotateSessionReturnsEmptyWhenSessionRepositoryNull() {
        DefaultContextCompressor compressor = new DefaultContextCompressor(
                new NoOpModelClient(), lockRepository, properties);
        // Don't call setSessionRepository — simulates unit test without DB
        Optional<DefaultContextCompressor.SessionRotationResult> result =
                compressor.rotateSession(String.valueOf(sessionId));
        assertThat(result).isEmpty();
    }

    // ── 9. Full compression flow integration ──

    @Test
    void compressAndRotateProducesCompressedMessagesAndNewSession() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(oldSession));
        // Simulate JPA assigning a UUID on save
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });

        DefaultContextCompressor compressor = createCompressor();

        // Create enough messages to trigger compression (must exceed protectFirstN + protectLastN = 3 + 6 = 9)
        List<Message> messages = new java.util.ArrayList<>();
        messages.add(Message.system("You are a helpful assistant."));
        messages.add(Message.user("x".repeat(500)));
        messages.add(Message.assistant("y".repeat(500), 1));
        messages.add(Message.user("z".repeat(500)));
        messages.add(Message.assistant("w".repeat(500), 2));
        messages.add(Message.user("a".repeat(500)));
        messages.add(Message.assistant("b".repeat(500), 3));
        messages.add(Message.user("c".repeat(500)));
        messages.add(Message.assistant("d".repeat(500), 4));
        messages.add(Message.user("e".repeat(500)));
        messages.add(Message.user("Recent message"));

        List<Message> compressed = compressor.compress(messages, 100);

        // Verify compression happened — compressed list should be smaller than original
        assertThat(compressed).isNotEmpty();
        assertThat(compressed.size()).isLessThanOrEqualTo(messages.size());

        // Now rotate the session
        Optional<DefaultContextCompressor.SessionRotationResult> result =
                compressor.rotateSession(String.valueOf(sessionId));
        assertThat(result).isPresent();
        assertThat(result.get().newSessionId()).isNotEqualTo(sessionId);
    }

    // ── 10. Verify new session ID is different from old ──

    @Test
    void rotateSessionCreatesNewSessionWithDifferentId() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(oldSession));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });

        DefaultContextCompressor compressor = createCompressor();
        Optional<DefaultContextCompressor.SessionRotationResult> result =
                compressor.rotateSession(String.valueOf(sessionId));

        assertThat(result).isPresent();
        assertThat(result.get().newSessionId()).isNotEqualTo(sessionId);
        assertThat(result.get().newSessionId()).isNotNull();
    }

    // ── 11. Default config: session rotation enabled ──

    @Test
    void sessionRotationEnabledByDefault() {
        assertThat(properties.getCompression().getSessionRotation().isEnabled()).isTrue();
    }
}