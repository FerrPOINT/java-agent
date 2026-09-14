package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DelegatedCompletionClassifierTest {

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            @Override public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    private static SessionEntity session(String endReason) {
        SessionEntity entity = new SessionEntity();
        entity.setEndReason(endReason);
        return entity;
    }

    @Test
    void nullParentIsTerminal() {
        var classifier = new DelegatedCompletionClassifier(providerOf(mock(SessionRepository.class)));
        assertThat(classifier.classify(null)).isEqualTo(DelegatedCompletionClassifier.Verdict.TERMINAL);
    }

    @Test
    void missingRepositoryIsRetry() {
        var classifier = new DelegatedCompletionClassifier(null);
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(DelegatedCompletionClassifier.Verdict.RETRY);
    }

    @Test
    void unknownParentIsTerminal() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(any())).thenReturn(Optional.empty());
        var classifier = new DelegatedCompletionClassifier(providerOf(repo));
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(DelegatedCompletionClassifier.Verdict.TERMINAL);
    }

    @Test
    void liveParentDelivers() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(any())).thenReturn(Optional.of(session(null)));
        var classifier = new DelegatedCompletionClassifier(providerOf(repo));
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(DelegatedCompletionClassifier.Verdict.DELIVER);
    }

    @Test
    void userBoundaryEndIsTerminalIdleTimeoutStaysDeliverable() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(any())).thenReturn(Optional.of(session("new_session")));
        var classifier = new DelegatedCompletionClassifier(providerOf(repo));
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(DelegatedCompletionClassifier.Verdict.TERMINAL);

        SessionRepository idleRepo = mock(SessionRepository.class);
        when(idleRepo.findById(any())).thenReturn(Optional.of(session("idle_timeout")));
        var idleClassifier = new DelegatedCompletionClassifier(providerOf(idleRepo));
        assertThat(idleClassifier.classify(UUID.randomUUID())).isEqualTo(DelegatedCompletionClassifier.Verdict.DELIVER);
    }

    @Test
    void compressionRotationWithoutTipRetries() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(any())).thenReturn(Optional.of(session("compression")));
        when(repo.findByParentSessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        var classifier = new DelegatedCompletionClassifier(providerOf(repo));
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(DelegatedCompletionClassifier.Verdict.RETRY);
    }

    @Test
    void compressionRotationWithLiveTipDelivers() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(any())).thenReturn(Optional.of(session("compression")));
        SessionEntity tip = session(null);
        when(repo.findByParentSessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of(tip));
        var classifier = new DelegatedCompletionClassifier(providerOf(repo));
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(DelegatedCompletionClassifier.Verdict.DELIVER);
    }

    @Test
    void compressionRotationWithEndedTipRetries() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(any())).thenReturn(Optional.of(session("compression")));
        SessionEntity endedTip = session("compression");
        when(repo.findByParentSessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of(endedTip));
        var classifier = new DelegatedCompletionClassifier(providerOf(repo));
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(DelegatedCompletionClassifier.Verdict.RETRY);
    }

    @Test
    void repositoryFailureRetries() {
        SessionRepository repo = mock(SessionRepository.class);
        when(repo.findById(any())).thenThrow(new RuntimeException("db down"));
        var classifier = new DelegatedCompletionClassifier(providerOf(repo));
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(DelegatedCompletionClassifier.Verdict.RETRY);
    }
}
