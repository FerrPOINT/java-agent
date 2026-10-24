package com.azhukov.agent.persistence.service;

import com.azhukov.agent.core.model.IdentityDeletionProtocol.*;
import com.azhukov.agent.core.security.NativeIdentityOrigin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Tests refusal and transaction-failure contracts through the real service.
 * JDBC doubles provide bounded rows/errors, not a fake SQL engine or fence state. */
class IdentityDeletionStoreFailureTest {
    private static final String ISSUER = "https://auth.example.test";
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("agent.central-auth.issuer", ISSUER);
    private final Deque<ResultSet> queryRows = new ArrayDeque<>();
    private SQLException commandFailure;
    private IdentityDeletionStore owner;

    @BeforeEach
    void setup() throws Exception {
        NativeIdentityOrigin.clear();
        when(dataSource.getConnection()).thenReturn(connection);
        // Every preparation gets a distinct statement, just as a real JDBC connection.
        // No SQL text is interpreted, matched, or reproduced by this fixture.
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            PreparedStatement statement = mock(PreparedStatement.class);
            when(statement.execute()).thenAnswer(execution -> {
                if (commandFailure != null) throw commandFailure;
                return false;
            });
            when(statement.getUpdateCount()).thenReturn(1);
            when(statement.executeQuery()).thenAnswer(query -> {
                assertThat(queryRows).as("unexpected extra JDBC query").isNotEmpty();
                return queryRows.removeFirst();
            });
            return statement;
        });
        owner = new IdentityDeletionStore(dataSource, environment, new ObjectMapper());
    }

    @AfterEach
    void clearRequestAuthority() { NativeIdentityOrigin.clear(); }

    private Request request() {
        return new Request(1, UUID.randomUUID(), new Identity(ISSUER, UUID.randomUUID().toString()), UUID.randomUUID());
    }

    private Window current() {
        return new Window(System.nanoTime() + Duration.ofSeconds(30).toNanos(), Instant.now().plusSeconds(30));
    }

    private void failure(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(Failure.class, error -> {
            assertThat(error.status()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(code);
            assertThat(error).hasMessage(code);
        });
    }

    private ResultSet rows(boolean present) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(present, false);
        return result;
    }

    private ResultSet clock() throws Exception {
        ResultSet result = rows(true);
        when(result.getObject(1, OffsetDateTime.class)).thenReturn(Instant.now().atOffset(ZoneOffset.UTC));
        return result;
    }

    private void answer(ResultSet... results) { queryRows.addAll(Arrays.asList(results)); }

    private void rolledBackWithoutReceipt() throws Exception {
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    void invalidCentralBindingCannotAcquireAConnectionOrEnrollAnAlias() throws Exception {
        String subject = UUID.randomUUID().toString();
        for (Identity identity : List.of(new Identity("https://other.example.test", subject),
                new Identity(ISSUER, "native-id"), new Identity(ISSUER, new UUID(0, 0).toString()),
                new Identity("https://user:fixture@auth.example.test", subject))) {
            failure(() -> owner.central(identity.issuer(), identity.subject(), current()),
                    401, "identity_binding_invalid");
        }
        verifyNoInteractions(dataSource, connection);
    }

    @Test
    void invalidNativeKeysAreUnknownWithoutPoolOrIdentityGuessing() throws Exception {
        for (String key : new String[]{null, "", " ", "a".repeat(1025)}) {
            failure(() -> owner.nativeStorage(key), 503, "identity_binding_unknown");
        }
        verifyNoInteractions(dataSource, connection);
    }

    @Test
    void expiredOriginalWindowCannotEnterAnyOwnerTransaction() throws Exception {
        Request request = request();
        Window expired = new Window(System.nanoTime() - 1, Instant.now().plusSeconds(300));
        for (Runnable command : List.<Runnable>of(() -> owner.check(request, expired),
                () -> owner.freeze(request, expired), () -> owner.release(request, expired),
                () -> owner.central(ISSUER, request.identity().subject(), expired))) {
            failure(command, 401, "identity_authority_expired");
        }
        verifyNoInteractions(dataSource, connection);
    }

    @Test
    void startupCannotGuessLegacyBindingOrUseForeignConfiguredIssuer() throws Exception {
        owner.init();
        verifyNoInteractions(dataSource, connection);
        environment.withProperty("agent.identity-deletion.legacy-issuer", "https://other.example.test");
        failure(owner::init, 503, "identity_deletion_storage_unavailable");
        verifyNoInteractions(dataSource, connection);
    }

    @Test
    void connectionAcquisitionFailureCannotBecomeAnEmptySummary() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("synthetic pool detail", "08006"));
        failure(() -> owner.check(request(), current()), 503, "identity_deletion_storage_unavailable");
        verifyNoInteractions(connection);
    }

    @Test
    void failedTransactionSetupClosesTheAcquiredConnectionWithoutACommit() throws Exception {
        doThrow(new SQLException("synthetic setup detail", "08006")).when(connection).setAutoCommit(false);
        failure(() -> owner.freeze(request(), current()), 503, "identity_deletion_storage_unavailable");
        verify(connection).close();
        verify(connection, never()).commit();
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void missingDatabaseClockRowRollsBackRatherThanInventingReceiptLifetime() throws Exception {
        answer(rows(false));
        failure(() -> owner.release(request(), current()), 503, "identity_deletion_storage_unavailable");
        rolledBackWithoutReceipt();
        assertThat(queryRows).isEmpty();
    }

    @Test
    void SQLFailureClassificationPreservesExactExpiryAndDoesNotHidePermissionOrSerializationErrors() throws Exception {
        record Case(SQLException error, int status, String code) {}
        for (Case test : List.of(
                new Case(new SQLException("synthetic guard detail", "23514"), 403, "identity_reference_rejected"),
                new Case(expired("java_identity_request_authority_expired", "java_identity_request_commit_clock"), 401, "identity_authority_expired"),
                new Case(expired("different_permission_failure", "java_identity_request_commit_clock"), 503, "identity_deletion_storage_unavailable"),
                new Case(expired("java_identity_request_authority_expired", "different_constraint"), 503, "identity_deletion_storage_unavailable"),
                new Case(new SQLException("synthetic other permission", "42501"), 503, "identity_deletion_storage_unavailable"),
                new Case(new SQLException("synthetic serialization", "40001"), 503, "identity_deletion_storage_unavailable"),
                new Case(new SQLException("synthetic deadlock", "40P01"), 503, "identity_deletion_storage_unavailable"))) {
            commandFailure = test.error();
            failure(() -> owner.check(request(), current()), test.status(), test.code());
            rolledBackWithoutReceipt();
            clearInvocations(connection);
        }
    }

    private SQLException expired(String message, String constraint) {
        // Actual pgjdbc public server-error decoder; no reflective or fabricated subclass fields.
        return new PSQLException(new ServerErrorMessage("SERROR\0C42501\0M" + message + "\0n" + constraint + "\0\0"));
    }

    @Test
    void rollbackFailureStillRetiresTheConnectionAndReturnsOnlyStaticFailure() throws Exception {
        commandFailure = new SQLException("synthetic operation detail", "XX000");
        doThrow(new SQLException("synthetic rollback detail", "08006")).when(connection).rollback();
        failure(() -> owner.release(request(), current()), 503, "identity_deletion_storage_unavailable");
        rolledBackWithoutReceipt();
    }

    @Test
    void alreadyKnownStorageAllowsUnattributedLookupButUnknownNativeKeyNeedsAuthority() throws Exception {
        ResultSet existingAlias = rows(true);
        answer(clock(), rows(true), existingAlias);
        owner.nativeStorage("own-existing-central-storage");
        verify(connection).commit();
        verify(connection, never()).rollback();
        verify(connection).close();
        assertThat(NativeIdentityOrigin.current()).isNull();
        assertThat(queryRows).isEmpty();
        clearInvocations(connection);
        answer(clock(), rows(true), rows(false));
        failure(() -> owner.nativeStorage("own-unknown-native-storage"), 503, "identity_binding_unknown");
        rolledBackWithoutReceipt();
        assertThat(queryRows).isEmpty();
        assertThat(NativeIdentityOrigin.current()).isNull();
    }
}
