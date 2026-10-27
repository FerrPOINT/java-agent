package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.ports.IdentityReferenceRegistration;
import com.azhukov.agent.core.ports.MessageStorePort;
import com.azhukov.agent.core.ports.SessionStorePort;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The deterministic session path must retain ownership and the native write fence. */
@ExtendWith(MockitoExtension.class)
class AgentSessionIdentityBoundaryTest {
    private static final String OWNER = "native-fixture-owner";
    private static final UUID SESSION = UUID.fromString("2e657254-e2cf-4b66-b62a-72e56af36454");
    @Mock private SessionStorePort sessions;
    @Mock private MessageStorePort messages;
    @Mock private TransactionTemplate transactions;
    @Mock private IdentityReferenceRegistration references;
    private AgentSessionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgentSessionResolver(sessions, Mappers.getMapper(SessionEntityMapper.class),
                transactions, messages, mock(SessionLineageService.class), mock(ProjectContextDetector.class));
        var beans = new StaticListableBeanFactory();
        beans.addBean("references", references);
        resolver.configureIdentityReferences(beans.getBeanProvider(IdentityReferenceRegistration.class));
        lenient().when(transactions.execute(any())).thenAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            return callback.doInTransaction(null);
        });
        UserContext.set(OWNER, UserContext.ROLE_USER);
    }

    @AfterEach
    void clearIdentity() {
        UserContext.clear();
    }

    @Test
    void aRejectedNativeReferencePreventsBothNewAndDeterministicStorage() {
        SecurityException fenced = new SecurityException("synthetic native fence");
        doThrow(fenced).when(references).nativeStorage(OWNER);

        assertThatThrownBy(() -> resolver.createSession(OWNER, "fixture", "model"))
                .isSameAs(fenced);
        assertThatThrownBy(() -> resolver.loadOrCreateSession(SESSION, OWNER, "fixture", "model", "api_server"))
                .isSameAs(fenced);
        verify(references, times(2)).nativeStorage(OWNER);
        verifyNoInteractions(transactions, sessions);
    }

    @Test
    void firstDeterministicTurnInsertsTheRequestedScopeAndReadsItsOwner() {
        SessionEntity stored = owned(OWNER);
        when(sessions.findById(SESSION)).thenReturn(Optional.empty(), Optional.of(stored));

        var result = resolver.loadOrCreateSession(SESSION, OWNER, "fixture", "model", "api_server");

        assertThat(result.id()).isEqualTo(SESSION);
        assertThat(result.userId()).isEqualTo(OWNER);
        var order = inOrder(references, sessions);
        order.verify(references).nativeStorage(OWNER);
        order.verify(sessions).findById(SESSION);
        order.verify(sessions).insertSessionRow(eq(SESSION), eq(OWNER), eq("New chat"),
                eq("fixture"), eq("model"), eq("api_server"), any(Instant.class));
        order.verify(sessions).findById(SESSION);
        verify(sessions, never()).save(any());
    }

    @Test
    void duplicateFirstTurnReturnsOnlyTheSameOwnersPersistedWinner() {
        SessionEntity stored = owned(OWNER);
        stored.setSource("telegram");
        stored.setProfile("work");
        stored.setSubgoal("owned task");
        stored.setCliState(Map.of("theme", "dark", "userDisplayName", "chosen name"));
        when(sessions.findById(SESSION)).thenReturn(Optional.empty(), Optional.of(stored));
        duplicateInsert(new DataIntegrityViolationException("synthetic duplicate"));

        var result = resolver.loadOrCreateSession(SESSION, OWNER, "fixture", "model", "api_server");

        assertThat(result.id()).isEqualTo(SESSION);
        assertThat(result.userId()).isEqualTo(OWNER);
        assertThat(result.metadata()).containsEntry("platform", "telegram").containsEntry("profile", "work")
                .containsEntry("subgoal", "owned task").containsEntry("theme", "dark")
                .containsEntry("userDisplayName", "chosen name");
        verify(sessions, never()).save(any());
    }

    @Test
    void aForeignDuplicateWinnerCannotBeHydratedOrReplaced() {
        when(sessions.findById(SESSION)).thenReturn(Optional.empty(), Optional.of(owned("foreign-owner")));
        duplicateInsert(new DataIntegrityViolationException("synthetic duplicate"));

        assertThatThrownBy(() -> resolver.loadOrCreateSession(SESSION, OWNER, "fixture", "model", "api_server"))
                .isInstanceOf(SecurityException.class);
        verify(sessions, never()).save(any());
        verify(sessions, times(2)).findById(SESSION);
    }

    @Test
    void anUnexplainedInsertFailureIsNotConvertedIntoAnOwnedSession() {
        var failure = new DataIntegrityViolationException("synthetic constraint failure");
        when(sessions.findById(SESSION)).thenReturn(Optional.empty());
        duplicateInsert(failure);

        assertThatThrownBy(() -> resolver.loadOrCreateSession(SESSION, OWNER, "fixture", "model", "api_server"))
                .isSameAs(failure);
        verify(sessions, never()).save(any());
    }

    @Test
    void anExistingOwnedScopeRetainsItsMetadataWithoutAnInsert() {
        var stored = owned(OWNER);
        stored.setSource("api_server");
        when(sessions.findById(SESSION)).thenReturn(Optional.of(stored));

        var result = resolver.loadOrCreateSession(SESSION, OWNER, "fixture", "model", "cli");

        assertThat(result.metadata()).containsEntry("userDisplayName", OWNER).containsEntry("platform", "api_server");
        verify(sessions, never()).insertSessionRow(any(), any(), any(), any(), any(), any(), any());
        verify(sessions, never()).save(any());
    }

    @Test
    void resumeDoesNotTurnAnOwnershipFailureIntoTheMissingSessionFallback() {
        when(messages.countBySessionIdAndActiveTrue(SESSION)).thenReturn(1L);
        when(sessions.findById(SESSION)).thenReturn(Optional.of(owned("foreign-owner")));

        assertThatThrownBy(() -> resolver.resolveOrCreate(SESSION, OWNER, "model"))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(references);
        verify(sessions, never()).save(any());
    }

    @Test
    void aNullExternalScopeStillRegistersBeforeCreatingTheInboundSession() {
        when(sessions.save(any())).thenAnswer(call -> {
            SessionEntity entity = call.getArgument(0);
            entity.setId(SESSION);
            return entity;
        });

        var created = resolver.loadOrCreateSession(null, OWNER, "fixture", "model", "telegram");

        assertThat(created.id()).isEqualTo(SESSION);
        assertThat(created.userId()).isEqualTo(OWNER);
        var order = inOrder(references, sessions);
        order.verify(references).nativeStorage(OWNER);
        order.verify(sessions).save(argThat(entity -> "telegram".equals(entity.getSource())
                && OWNER.equals(entity.getUserId())));
        verify(sessions, never()).findById(any());
    }

    private void duplicateInsert(DataIntegrityViolationException failure) {
        doThrow(failure).when(sessions).insertSessionRow(eq(SESSION), eq(OWNER), eq("New chat"),
                eq("fixture"), eq("model"), eq("api_server"), any(Instant.class));
    }

    private static SessionEntity owned(String owner) {
        var entity = new SessionEntity();
        entity.setId(SESSION);
        entity.setUserId(owner);
        entity.setTitle("Owned fixture");
        entity.setModelProvider("fixture");
        entity.setModelName("model");
        return entity;
    }
}
