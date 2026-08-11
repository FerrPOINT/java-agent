package com.azhukov.agent.tools.terminal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for {@link ShellHookManager}.
 * Covers config parsing, hook registration, response parsing, edge cases.
 */
class ShellHookManagerBranchTest {

    // ── ShellHookSpec ──

    @Test
    void shellHookSpec_nullCommand_handled() {
        ShellHookManager.ShellHookSpec spec = new ShellHookManager.ShellHookSpec("pre_tool_call", null, null, 60);
        assertThat(spec.command()).isNull();
    }

    @Test
    void shellHookSpec_blankMatcher_treatedAsNull() {
        ShellHookManager.ShellHookSpec spec = new ShellHookManager.ShellHookSpec("pre_tool_call", "cmd", "  ", 60);
        assertThat(spec.matcher()).isNull();
    }

    @Test
    void shellHookSpec_emptyMatcher_treatedAsNull() {
        ShellHookManager.ShellHookSpec spec = new ShellHookManager.ShellHookSpec("pre_tool_call", "cmd", "", 60);
        assertThat(spec.matcher()).isNull();
    }

    @Test
    void shellHookSpec_timeoutClampedToMax() {
        ShellHookManager.ShellHookSpec spec = new ShellHookManager.ShellHookSpec("pre_tool_call", "cmd", null, 99999);
        assertThat(spec.timeout()).isLessThanOrEqualTo(300);
    }

    @Test
    void shellHookSpec_timeoutBelowOne_clampedToOne() {
        ShellHookManager.ShellHookSpec spec = new ShellHookManager.ShellHookSpec("pre_tool_call", "cmd", null, 0);
        assertThat(spec.timeout()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shellHookSpec_invalidRegexMatcher_fallsBackToLiteral() {
        ShellHookManager.ShellHookSpec spec = new ShellHookManager.ShellHookSpec("pre_tool_call", "cmd", "[invalid", 60);
        // Invalid regex → compiledMatcher is null → literal equality
        assertThat(spec.matchesTool("terminal")).isFalse();
        assertThat(spec.matchesTool("[invalid")).isTrue();
    }

    @Test
    void shellHookSpec_validRegexMatcher_matchesCorrectly() {
        ShellHookManager.ShellHookSpec spec = new ShellHookManager.ShellHookSpec("pre_tool_call", "cmd", "term.*", 60);
        assertThat(spec.matchesTool("terminal")).isTrue();
        assertThat(spec.matchesTool("browser")).isFalse();
    }

    @Test
    void shellHookSpec_nullMatcher_matchesAll() {
        ShellHookManager.ShellHookSpec spec = new ShellHookManager.ShellHookSpec("pre_tool_call", "cmd", null, 60);
        assertThat(spec.matchesTool("anything")).isTrue();
        assertThat(spec.matchesTool(null)).isTrue();
    }

    @Test
    void shellHookSpec_nullMatcher_withNullToolName_returnsTrue() {
        ShellHookManager.ShellHookSpec spec = new ShellHookManager.ShellHookSpec("pre_tool_call", "cmd", null, 60);
        assertThat(spec.matchesTool(null)).isTrue();
    }

    @Test
    void shellHookSpec_nonNullMatcher_withNullToolName_returnsFalse() {
        ShellHookManager.ShellHookSpec spec = new ShellHookManager.ShellHookSpec("pre_tool_call", "cmd", "terminal", 60);
        assertThat(spec.matchesTool(null)).isFalse();
    }

    // ── registerFromConfig ──

    @Test
    void registerFromConfig_nullConfig_returnsEmpty() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        assertThat(mgr.registerFromConfig(null)).isEmpty();
    }

    @Test
    void registerFromConfig_emptyConfig_returnsEmpty() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        assertThat(mgr.registerFromConfig(Map.of())).isEmpty();
    }

    @Test
    void registerFromConfig_unknownEvent_skipped() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        Map<String, Object> config = Map.of("unknown_event", List.of(Map.of("command", "echo test")));
        assertThat(mgr.registerFromConfig(config)).isEmpty();
    }

    @Test
    void registerFromConfig_nonListEntries_skipped() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        Map<String, Object> config = Map.of("pre_tool_call", "not a list");
        assertThat(mgr.registerFromConfig(config)).isEmpty();
    }

    @Test
    void registerFromConfig_nullEntries_skipped() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("pre_tool_call", null);
        assertThat(mgr.registerFromConfig(config)).isEmpty();
    }

    @Test
    void registerFromConfig_nonMapEntry_skipped() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        Map<String, Object> config = Map.of("pre_tool_call", List.of("not a map"));
        assertThat(mgr.registerFromConfig(config)).isEmpty();
    }

    @Test
    void registerFromConfig_nullCommand_skipped() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        Map<String, Object> config = Map.of("pre_tool_call", List.of(Map.of("command", "")));
        assertThat(mgr.registerFromConfig(config)).isEmpty();
    }

    @Test
    void registerFromConfig_blankCommand_skipped() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        Map<String, Object> config = Map.of("pre_tool_call", List.of(Map.of("command", "  ")));
        assertThat(mgr.registerFromConfig(config)).isEmpty();
    }

    @Test
    void registerFromConfig_validHook_registered() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes-hooks-valid", true);
        Map<String, Object> config = Map.of("pre_tool_call", List.of(Map.of("command", "echo hook")));
        List<ShellHookManager.ShellHookSpec> result = mgr.registerFromConfig(config);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).command()).isEqualTo("echo hook");
    }

    @Test
    void registerFromConfig_matcherOnNonToolCallEvent_ignored() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes-hooks-matcher", true);
        Map<String, Object> config = Map.of("on_session_start", List.of(
            Map.of("command", "echo start", "matcher", "terminal")
        ));
        List<ShellHookManager.ShellHookSpec> result = mgr.registerFromConfig(config);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).matcher()).isNull();
    }

    @Test
    void registerFromConfig_timeoutAsString_parsed() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes-hooks-timeout-str", true);
        Map<String, Object> config = Map.of("pre_tool_call", List.of(
            Map.of("command", "echo test", "timeout", "30")
        ));
        List<ShellHookManager.ShellHookSpec> result = mgr.registerFromConfig(config);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).timeout()).isEqualTo(30);
    }

    @Test
    void registerFromConfig_timeoutInvalidString_usesDefault() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes-hooks-invalid-timeout", true);
        Map<String, Object> config = Map.of("pre_tool_call", List.of(
            Map.of("command", "echo test", "timeout", "not-a-number")
        ));
        List<ShellHookManager.ShellHookSpec> result = mgr.registerFromConfig(config);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).timeout()).isEqualTo(60); // default
    }

    @Test
    void registerFromConfig_timeoutBelowOne_usesDefault() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes-hooks-below1", true);
        Map<String, Object> config = Map.of("pre_tool_call", List.of(
            Map.of("command", "echo test", "timeout", -1)
        ));
        List<ShellHookManager.ShellHookSpec> result = mgr.registerFromConfig(config);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).timeout()).isEqualTo(60);
    }

    @Test
    void registerFromConfig_duplicateHooks_idempotent() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes-hooks-dup", true);
        Map<String, Object> config = Map.of("pre_tool_call", List.of(
            Map.of("command", "echo dup"),
            Map.of("command", "echo dup")
        ));
        List<ShellHookManager.ShellHookSpec> result = mgr.registerFromConfig(config);
        assertThat(result).hasSize(1); // Second identical hook skipped
    }

    @Test
    void registerFromConfig_noAcceptHooks_notInAllowlist_skipped() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes-no-accept", false);
        // No allowlist file exists, no console → skipped
        Map<String, Object> config = Map.of("pre_tool_call", List.of(
            Map.of("command", "echo test")
        ));
        List<ShellHookManager.ShellHookSpec> result = mgr.registerFromConfig(config);
        // Should be skipped because not allowlisted and can't prompt
        assertThat(result).isEmpty();
    }

    // ── HookResponse ──

    @Test
    void hookResponse_noop_isNotBlocked() {
        ShellHookManager.HookResponse response = ShellHookManager.HookResponse.noop();
        assertThat(response.blocked()).isFalse();
        assertThat(response.message()).isNull();
        assertThat(response.context()).isNull();
    }

    // ── invokePreToolCall ──

    @Test
    void invokePreToolCall_noHooks_returnsNoop() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        ShellHookManager.HookResponse response = mgr.invokePreToolCall("terminal", "ls -la");
        assertThat(response.blocked()).isFalse();
    }

    @Test
    void invokePostToolCall_noHooks_doesNotThrow() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        mgr.invokePostToolCall("terminal", "ls -la", 0, "output");
    }

    // ── getHooksForEvent ──

    @Test
    void getHooksForEvent_noHooks_returnsEmpty() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        assertThat(mgr.getHooksForEvent("pre_tool_call")).isEmpty();
    }

    @Test
    void getRegisteredHooks_emptyInitially() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes", true);
        assertThat(mgr.getRegisteredHooks()).isEmpty();
    }

    // ── reset ──

    @Test
    void reset_clearsAllHooks() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes-reset", true);
        Map<String, Object> config = Map.of("pre_tool_call", List.of(Map.of("command", "echo test")));
        mgr.registerFromConfig(config);
        assertThat(mgr.getRegisteredHooks()).isNotEmpty();

        mgr.reset();
        assertThat(mgr.getRegisteredHooks()).isEmpty();
    }

    // ── Constructor with null hermesHome ──

    @Test
    void constructor_nullHermesHome_usesDefaultPath() {
        ShellHookManager mgr = new ShellHookManager(null, true);
        // Should not throw — uses user home fallback
        assertThat(mgr.getRegisteredHooks()).isEmpty();
    }

    @Test
    void constructor_singleArg_usesAcceptFalse() {
        ShellHookManager mgr = new ShellHookManager("/tmp/test-hermes-single");
        // Single-arg constructor defaults to acceptHooks=false
        Map<String, Object> config = Map.of("pre_tool_call", List.of(Map.of("command", "echo test")));
        assertThat(mgr.registerFromConfig(config)).isEmpty();
    }
}