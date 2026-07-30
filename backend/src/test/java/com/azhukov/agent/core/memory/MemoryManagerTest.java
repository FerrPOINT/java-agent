package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MemoryManager}.
 */
@ExtendWith(MockitoExtension.class)
class MemoryManagerTest {

    private MemoryManager manager;

    @BeforeEach
    void setUp() {
        manager = new MemoryManager();
    }

    @Test
    void addBuiltinProvider_isRegistered() {
        MemoryProvider provider = mock(MemoryProvider.class);
        manager.addBuiltinProvider(provider);
        assertThat(manager.getProviders()).hasSize(1);
        assertThat(manager.hasProviders()).isTrue();
    }

    @Test
    void addExternalProvider_isRegistered() {
        MemoryProvider provider = mock(MemoryProvider.class);
        manager.addProvider(provider, "external-db");
        assertThat(manager.getProviders()).hasSize(1);
        assertThat(manager.hasProviders()).isTrue();
    }

    @Test
    void secondExternalProvider_isRejected() {
        MemoryProvider first = mock(MemoryProvider.class);
        MemoryProvider second = mock(MemoryProvider.class);
        manager.addProvider(first, "external-a");
        manager.addProvider(second, "external-b");
        // Only the first external provider should be registered
        assertThat(manager.getProviders()).hasSize(1);
    }

    @Test
    void builtinAndExternal_bothRegistered() {
        MemoryProvider builtin = mock(MemoryProvider.class);
        MemoryProvider external = mock(MemoryProvider.class);
        manager.addBuiltinProvider(builtin);
        manager.addProvider(external, "external");
        assertThat(manager.getProviders()).hasSize(2);
    }

    @Test
    void getPrimaryProvider_returnsFirst() {
        MemoryProvider first = mock(MemoryProvider.class);
        MemoryProvider second = mock(MemoryProvider.class);
        manager.addBuiltinProvider(first);
        manager.addProvider(second, "external");
        assertThat(manager.getPrimaryProvider()).isSameAs(first);
    }

    @Test
    void getPrimaryProvider_emptyReturnsNull() {
        assertThat(manager.getPrimaryProvider()).isNull();
        assertThat(manager.hasProviders()).isFalse();
    }

    @Test
    void prefetchAll_callsAllProviders() {
        MemoryProvider p1 = mock(MemoryProvider.class);
        MemoryProvider p2 = mock(MemoryProvider.class);
        manager.addBuiltinProvider(p1);
        manager.addProvider(p2, "ext");
        manager.prefetchAll("query", "session-1");
        verify(p1).prefetch("query", "session-1");
        verify(p2).prefetch("query", "session-1");
    }

    @Test
    void prefetchAll_providerFailure_doesNotBlockOthers() {
        MemoryProvider p1 = mock(MemoryProvider.class);
        MemoryProvider p2 = mock(MemoryProvider.class);
        doThrow(new RuntimeException("fail")).when(p1).prefetch(any(), any());
        manager.addBuiltinProvider(p1);
        manager.addProvider(p2, "ext");
        manager.prefetchAll("query", "session-1");
        verify(p1).prefetch("query", "session-1");
        verify(p2).prefetch("query", "session-1");
    }

    @Test
    void queuePrefetchAll_withNoProviders_isNoOp() {
        manager.queuePrefetchAll("query", "session-1");
        // No exception, no error
    }

    @Test
    void queuePrefetchAll_submitsBackgroundWork() throws Exception {
        MemoryProvider provider = mock(MemoryProvider.class);
        manager.addBuiltinProvider(provider);
        manager.queuePrefetchAll("query", "session-1");
        // Wait for background task to complete
        Thread.sleep(200);
        verify(provider, timeout(1000)).prefetch("query", "session-1");
        manager.shutdown();
    }

    @Test
    void syncAll_submitsBackgroundWork() throws Exception {
        MemoryProvider provider = mock(MemoryProvider.class);
        manager.addBuiltinProvider(provider);
        List<Message> messages = List.of(Message.user("hello"), Message.assistant("hi", 0));
        manager.syncAll("session-1", messages);
        Thread.sleep(200);
        verify(provider, timeout(1000)).syncTurn("session-1", messages);
        manager.shutdown();
    }

    @Test
    void onTurnStart_triggersPrefetch() {
        MemoryProvider provider = mock(MemoryProvider.class);
        manager.addBuiltinProvider(provider);
        manager.onTurnStart("session-1", "what is my name?");
        verify(provider).prefetch("what is my name?", "session-1");
    }

    @Test
    void onSessionSwitch_notifiesProviders() {
        MemoryProvider provider = mock(MemoryProvider.class);
        manager.addBuiltinProvider(provider);
        manager.onSessionSwitch("old-session", "new-session");
        verify(provider).onSessionEnd("old-session");
        verify(provider).onSessionStart("new-session");
    }

    @Test
    void onSessionSwitch_nullOldSession_skipsEnd() {
        MemoryProvider provider = mock(MemoryProvider.class);
        manager.addBuiltinProvider(provider);
        manager.onSessionSwitch(null, "new-session");
        verify(provider, never()).onSessionEnd(any());
        verify(provider).onSessionStart("new-session");
    }

    @Test
    void onPreCompress_doesNotThrow() {
        manager.onPreCompress("session-1");
    }

    @Test
    void onDelegation_doesNotThrow() {
        manager.onDelegation("session-1", "do something");
    }

    @Test
    void onMemoryWrite_doesNotThrow() {
        manager.onMemoryWrite("session-1", "preference", "likes dark mode");
    }

    @Test
    void shutdown_withNoExecutor_isNoOp() {
        manager.shutdown();
    }

    @Test
    void shutdown_drainsAndStops() throws Exception {
        MemoryProvider provider = mock(MemoryProvider.class);
        manager.addBuiltinProvider(provider);
        manager.queuePrefetchAll("query", "session-1");
        Thread.sleep(100);
        manager.shutdown();
        // After shutdown, the executor is terminated
        // Verify no exception
    }

    @Test
    void injectTools_emptySchemas_addsNothing() {
        int added = manager.injectTools(new java.util.ArrayList<>(), new java.util.HashSet<>());
        assertThat(added).isZero();
    }

    @Test
    void getToolSchemas_returnsEmptyByDefault() {
        assertThat(manager.getToolSchemas()).isEmpty();
    }

    @Test
    void buildSystemPrompt_emptyProviders_returnsEmpty() {
        assertThat(manager.buildSystemPrompt()).isEmpty();
    }
}