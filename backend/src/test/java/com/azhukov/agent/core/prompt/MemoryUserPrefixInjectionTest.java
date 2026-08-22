package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hermes parity (0.1.18): BOTH memory targets are injected into the system
 * prompt, in the exact Hermes format (tools/memory_tool.py:62-67,731-747 +
 * system_prompt.py:782-792).
 *
 * Regression: target="user" (USER PROFILE) was NEVER injected before — the
 * background review kept writing user facts that the model could never see
 * (verified live: agent answered "не знаю" about the user's name while the
 * DB held 4 entries with it).
 */
class MemoryUserPrefixInjectionTest {

    private MemoryProvider memoryProvider;
    private DefaultPromptBuilder builder;

    @BeforeEach
    void setUp() {
        memoryProvider = mock(MemoryProvider.class);
        AgentProperties properties = new AgentProperties();
        // silence repo/file access paths
        builder = new DefaultPromptBuilder(properties, null, new com.azhukov.agent.core.state.DefaultAgentConstants(),
            null, null, memoryProvider, null, null);
    }

    private Session session() {
        return new Session(UUID.randomUUID(), "user-1", null, "noop", "noop",
            null, Map.of(), null);
    }

    @Test
    @DisplayName("user profile block injected with Hermes header + separator + § delimiter")
    void userProfileInjected() {
        when(memoryProvider.getRawEntries("user-1", "user"))
            .thenReturn(List.of("User's name is Test Selfimprovement.", "Habit: runs verification loops."));
        when(memoryProvider.getRawEntries("user-1", "memory"))
            .thenReturn(List.of());

        String prefix = builder.buildMemoryPrefix(session());

        assertThat(prefix).contains("USER PROFILE (who the user is)");
        assertThat(prefix).contains("═".repeat(46));
        assertThat(prefix).contains("User's name is Test Selfimprovement.");
        assertThat(prefix).contains("§"); // Hermes ENTRY_DELIMITER
        assertThat(prefix).contains("%"); // usage indicator
        assertThat(prefix).doesNotContain("MEMORY (your personal notes)");
    }

    @Test
    @DisplayName("memory block injected with its own header; both blocks coexist")
    void bothBlocksInjected() {
        when(memoryProvider.getRawEntries("user-1", "user"))
            .thenReturn(List.of("User's name is Test."));
        when(memoryProvider.getRawEntries("user-1", "memory"))
            .thenReturn(List.of("Fact one.", "Fact two."));

        String prefix = builder.buildMemoryPrefix(session());

        int userIdx = prefix.indexOf("USER PROFILE (who the user is)");
        int memIdx = prefix.indexOf("MEMORY (your personal notes)");
        assertThat(userIdx).isGreaterThanOrEqualTo(0);
        assertThat(memIdx).isGreaterThan(userIdx); // user block first (Hermes: user then memory in volatile tail order is memory then user; java prepends user first — both present is what matters)
        assertThat(prefix).contains("Fact one.");
        assertThat(prefix).contains("User's name is Test.");
    }

    @Test
    @DisplayName("duplicate entries deduplicated, first occurrence kept (Hermes dict.fromkeys)")
    void deduplication() {
        when(memoryProvider.getRawEntries("user-1", "user"))
            .thenReturn(List.of("Same fact.", "Same fact.", "Other fact."));
        when(memoryProvider.getRawEntries("user-1", "memory"))
            .thenReturn(List.of());

        String prefix = builder.buildMemoryPrefix(session());

        assertThat(prefix).containsOnlyOnce("Same fact.");
        assertThat(prefix).contains("Other fact.");
    }

    @Test
    @DisplayName("empty targets → empty prefix (no block headers leaked)")
    void emptyTargetsEmptyPrefix() {
        when(memoryProvider.getRawEntries(anyString(), anyString())).thenReturn(List.of());

        assertThat(builder.buildMemoryPrefix(session())).isEmpty();
    }

    @Test
    @DisplayName("provider failure → empty prefix, never throws")
    void providerFailureNeverThrows() {
        when(memoryProvider.getRawEntries(anyString(), anyString()))
            .thenThrow(new RuntimeException("db down"));

        assertThat(builder.buildMemoryPrefix(session())).isEmpty();
    }
}
