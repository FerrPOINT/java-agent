package com.azhukov.agent.config;

import com.azhukov.agent.core.security.NativeIdentityOrigin;
import com.azhukov.agent.core.security.NativeIdentityOrigin.Kind;
import com.azhukov.agent.core.security.NativeIdentityOrigin.Origin;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JDBC wrapper over JDBC-port doubles: transaction-local provenance must
 * survive statement variants but cannot leak across identities or transactions. */
class NativeIdentityDataSourceTest {
    private static final String BUSINESS = "UPDATE own_fixture SET value = ?";
    private static final String BIND = "SELECT set_config('pdlc.java.native_kind',?,true),"
            + "set_config('pdlc.java.native_principal',?,true),set_config('pdlc.java.native_namespace',?,true)";
    private final DataSource source = mock(DataSource.class);
    private final Connection delegate = mock(Connection.class);
    private final PreparedStatement binding = mock(PreparedStatement.class);
    private final PreparedStatement writer = mock(PreparedStatement.class);
    private final Statement statement = mock(Statement.class);
    private final CallableStatement callable = mock(CallableStatement.class);
    private NativeIdentityDataSource.ProvenanceDataSource dataSource;

    @BeforeEach
    void setup() throws Exception {
        NativeIdentityOrigin.clear();
        when(source.getConnection()).thenReturn(delegate);
        when(delegate.getAutoCommit()).thenReturn(false);
        when(delegate.prepareStatement(BUSINESS)).thenReturn(writer);
        when(delegate.prepareStatement(BIND)).thenReturn(binding);
        when(delegate.createStatement()).thenReturn(statement);
        when(delegate.prepareCall("{call own_fixture()}")).thenReturn(callable);
        dataSource = new NativeIdentityDataSource.ProvenanceDataSource(source);
    }

    @AfterEach
    void clearRequestProvenance() { NativeIdentityOrigin.clear(); }

    @Test
    void beanDecorationIsIdempotentAndDoesNotReplaceUnrelatedBeans() {
        var processor = new NativeIdentityDataSource();
        Object unchanged = new Object();
        assertThat(processor.postProcessAfterInitialization(unchanged, "other")).isSameAs(unchanged);
        Object wrapped = processor.postProcessAfterInitialization(source, "dataSource");
        assertThat(wrapped).isInstanceOf(NativeIdentityDataSource.ProvenanceDataSource.class);
        assertThat(processor.postProcessAfterInitialization(wrapped, "again")).isSameAs(wrapped);
        assertThat(processor.postProcessAfterInitialization(dataSource, "ours")).isSameAs(dataSource);
    }

    @Test
    void credentialedConnectionKeepsPoolOwnershipAndReturnsTheSameGuardedStatementConnection() throws Exception {
        when(source.getConnection("fixture-user", "fixture-only")).thenReturn(delegate);
        Connection connection = dataSource.getConnection("fixture-user", "fixture-only");
        PreparedStatement prepared = connection.prepareStatement(BUSINESS);
        assertThat(prepared).isNotSameAs(writer);
        assertThat(prepared.getConnection()).isSameAs(connection);
        connection.close();
        verify(source).getConnection("fixture-user", "fixture-only");
        verify(delegate).close();
        verifyNoInteractions(binding);
    }

    @Test
    void provenanceIsBoundOnFirstExecutionNotPreparationAndOnlyOnceForSameTransaction() throws Exception {
        NativeIdentityOrigin.bind(new Origin(Kind.USER_API_KEY, "own-native-user", false));
        Connection connection = dataSource.getConnection();
        PreparedStatement prepared = connection.prepareStatement(BUSINESS);
        verifyNoInteractions(binding);
        prepared.setString(1, "own-synthetic-value");
        verify(writer).setString(1, "own-synthetic-value");
        prepared.executeUpdate();
        prepared.executeQuery();
        verify(delegate).prepareStatement(BIND);
        verify(binding).setString(1, "USER_API_KEY");
        verify(binding).setString(2, "own-native-user");
        verify(binding).setString(3, "false");
        verify(binding).execute();
        verify(binding).close();
        verify(writer).executeUpdate();
        verify(writer).executeQuery();
    }

    @Test
    void statementCallableAndBatchCannotBypassTheSameRequestProvenance() throws Exception {
        NativeIdentityOrigin.bind(new Origin(Kind.GATEWAY_KEY, "own-gateway", true));
        Connection connection = dataSource.getConnection();
        Statement plain = connection.createStatement();
        CallableStatement procedure = connection.prepareCall("{call own_fixture()}");
        assertThat(plain.getConnection()).isSameAs(connection);
        assertThat(procedure.getConnection()).isSameAs(connection);
        plain.addBatch("UPDATE own_fixture SET value = 'fixture'");
        procedure.execute();
        plain.executeBatch();
        verify(binding).setString(1, "GATEWAY_KEY");
        verify(binding).setString(2, "own-gateway");
        verify(binding).setString(3, "true");
        verify(binding).execute();
        verify(statement).addBatch("UPDATE own_fixture SET value = 'fixture'");
        verify(callable).execute();
        verify(statement).executeBatch();
    }

    @Test
    void centralRequestExplicitlyClearsNativeSettingsInsteadOfReusingPoolIdentity() throws Exception {
        NativeIdentityOrigin.bind(new Origin(Kind.GATEWAY_EVENT, "own-native-event", true));
        PreparedStatement prepared = dataSource.getConnection().prepareStatement(BUSINESS);
        prepared.executeUpdate();
        NativeIdentityOrigin.clear();
        prepared.executeUpdate();
        verify(binding).setString(1, "GATEWAY_EVENT");
        verify(binding).setString(2, "own-native-event");
        verify(binding).setString(1, "");
        verify(binding).setString(2, "");
        verify(binding).setString(3, "false");
        verify(binding, times(2)).execute();
        verify(binding, times(2)).close();
    }

    @Test
    void changedPrincipalWithinTransactionCannotKeepTheFirstPrincipalsAuthority() throws Exception {
        NativeIdentityOrigin.bind(new Origin(Kind.USER_API_KEY, "own-first", false));
        PreparedStatement prepared = dataSource.getConnection().prepareStatement(BUSINESS);
        prepared.executeUpdate();
        NativeIdentityOrigin.bind(new Origin(Kind.USER_API_KEY, "own-second", false));
        prepared.executeUpdate();
        verify(binding).setString(2, "own-first");
        verify(binding).setString(2, "own-second");
        verify(binding, times(2)).execute();
    }

    @Test
    void commitRollbackAndAutoCommitTransitionRequireFreshBinding() throws Exception {
        NativeIdentityOrigin.bind(new Origin(Kind.DEVELOPMENT, "own-development", true));
        Connection connection = dataSource.getConnection();
        PreparedStatement prepared = connection.prepareStatement(BUSINESS);
        prepared.executeUpdate();
        connection.commit();
        prepared.executeUpdate();
        connection.rollback();
        prepared.executeUpdate();
        connection.setAutoCommit(true);
        connection.setAutoCommit(false);
        prepared.executeUpdate();
        verify(delegate).commit();
        verify(delegate).rollback();
        verify(delegate).setAutoCommit(true);
        verify(delegate).setAutoCommit(false);
        verify(binding, times(4)).execute();
        verify(writer, times(4)).executeUpdate();
    }

    @Test
    void autocommitStatementsDoNotPretendTransactionLocalSettingsAreDurable() throws Exception {
        when(delegate.getAutoCommit()).thenReturn(true);
        NativeIdentityOrigin.bind(new Origin(Kind.USER_API_KEY, "own-autocommit", false));
        dataSource.getConnection().prepareStatement(BUSINESS).executeUpdate();
        verifyNoInteractions(binding);
        verify(writer).executeUpdate();
    }

    @Test
    void failedProvenanceBindingStopsWriterAndDoesNotCacheFalseSuccess() throws Exception {
        SQLException failure = new SQLException("own-binding-failure", "23514");
        when(binding.execute()).thenThrow(failure).thenReturn(true);
        PreparedStatement prepared = dataSource.getConnection().prepareStatement(BUSINESS);
        assertThatThrownBy(prepared::executeUpdate).isSameAs(failure);
        verify(writer, never()).executeUpdate();
        prepared.executeUpdate();
        verify(binding, times(2)).execute();
        verify(writer).executeUpdate();
    }

    @Test
    void businessSQLExceptionIsUnwrappedWithoutChangingDatabaseClassification() throws Exception {
        SQLException failure = new SQLException("own-business-failure", "42501");
        when(writer.executeUpdate()).thenThrow(failure);
        PreparedStatement prepared = dataSource.getConnection().prepareStatement(BUSINESS);
        assertThatThrownBy(prepared::executeUpdate).isSameAs(failure);
        verify(binding).execute();
    }
}
