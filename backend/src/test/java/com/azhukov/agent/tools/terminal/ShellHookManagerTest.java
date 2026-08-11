package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.tools.terminal.ShellHookManager.HookResponse;
import com.azhukov.agent.tools.terminal.ShellHookManager.ShellHookSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ShellHookManager} — ported from Hermes shell_hooks.py patterns.
 */
class ShellHookManagerTest {

    // ─── ShellHookSpec tests ───

    @Test
    void spec_noMatcher_matchesAllTools() {
        ShellHookSpec spec = new ShellHookSpec("pre_tool_call", "/hook.sh", null, 60);
        assertThat(spec.matchesTool("terminal")).isTrue();
        assertThat(spec.matchesTool("file")).isTrue();
        assertThat(spec.matchesTool(null)).isTrue();
    }

    @Test
    void spec_withMatcher_matchesSpecificTool() {
        ShellHookSpec spec = new ShellHookSpec("pre_tool_call", "/hook.sh", "terminal", 60);
        assertThat(spec.matchesTool("terminal")).isTrue();
        assertThat(spec.matchesTool("file")).isFalse();
        assertThat(spec.matchesTool(null)).isFalse();
    }

    @Test
    void spec_regexMatcher_matchesPattern() {
        ShellHookSpec spec = new ShellHookSpec("pre_tool_call", "/hook.sh", "term.*", 60);
        assertThat(spec.matchesTool("terminal")).isTrue();
        assertThat(spec.matchesTool("term")).isTrue();
        assertThat(spec.matchesTool("file")).isFalse();
    }

    @Test
    void spec_invalidRegex_fallsBackToLiteral() {
        ShellHookSpec spec = new ShellHookSpec("pre_tool_call", "/hook.sh", "[invalid", 60);
        // Invalid regex → literal equality
        assertThat(spec.matchesTool("[invalid")).isTrue();
        assertThat(spec.matchesTool("terminal")).isFalse();
    }

    @Test
    void spec_whitespaceMatcher_isStripped() {
        ShellHookSpec spec = new ShellHookSpec("pre_tool_call", "/hook.sh", "  terminal  ", 60);
        assertThat(spec.matchesTool("terminal")).isTrue();
    }

    @Test
    void spec_emptyMatcher_treatedAsNull() {
        ShellHookSpec spec = new ShellHookSpec("pre_tool_call", "/hook.sh", "  ", 60);
        assertThat(spec.matcher()).isNull();
        assertThat(spec.matchesTool("terminal")).isTrue();
    }

    @Test
    void spec_timeoutClampedToMax() {
        ShellHookSpec spec = new ShellHookSpec("pre_tool_call", "/hook.sh", null, 999);
        assertThat(spec.timeout()).isEqualTo(300);
    }

    @Test
    void spec_timeoutMinimumEnforced() {
        ShellHookSpec spec = new ShellHookSpec("pre_tool_call", "/hook.sh", null, 0);
        assertThat(spec.timeout()).isEqualTo(1);
    }

    // ─── Registration tests ───

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
    void registerFromConfig_validHook_registersSuccessfully(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", "/bin/true", "matcher", "terminal", "timeout", 10)
        ));
        List<ShellHookSpec> registered = mgr.registerFromConfig(config);
        assertThat(registered).hasSize(1);
        assertThat(registered.get(0).event()).isEqualTo("pre_tool_call");
        assertThat(registered.get(0).command()).isEqualTo("/bin/true");
        assertThat(registered.get(0).matcher()).isEqualTo("terminal");
    }

    @Test
    void registerFromConfig_unknownEvent_skipped(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("unknown_event", List.of(
            Map.of("command", "/bin/true")
        ));
        assertThat(mgr.registerFromConfig(config)).isEmpty();
    }

    @Test
    void registerFromConfig_missingCommand_skipped(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("matcher", "terminal")
        ));
        assertThat(mgr.registerFromConfig(config)).isEmpty();
    }

    @Test
    void registerFromConfig_blankCommand_skipped(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", "  ")
        ));
        assertThat(mgr.registerFromConfig(config)).isEmpty();
    }

    @Test
    void registerFromConfig_idempotent_secondRegistrationSkipped(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", "/bin/true", "matcher", "terminal")
        ));
        mgr.registerFromConfig(config);
        var second = mgr.registerFromConfig(config);
        assertThat(second).isEmpty(); // already registered
    }

    @Test
    void registerFromConfig_matcherIgnoredForNonToolEvents(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("on_session_start", List.of(
            Map.of("command", "/bin/true", "matcher", "terminal")
        ));
        List<ShellHookSpec> registered = mgr.registerFromConfig(config);
        assertThat(registered).hasSize(1);
        // matcher should be null (ignored for non-tool events)
        assertThat(registered.get(0).matcher()).isNull();
    }

    // ─── Hook invocation tests ───

    @Test
    void invokePreToolCall_noHooksRegistered_returnsNoop(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        HookResponse response = mgr.invokePreToolCall("terminal", "ls -la");
        assertThat(response.blocked()).isFalse();
    }

    @Test
    void invokePreToolCall_hookDoesNotBlock_returnsNoop(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        // /bin/true exits 0 with no output → no block
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", "/bin/true", "matcher", "terminal")
        ));
        mgr.registerFromConfig(config);
        HookResponse response = mgr.invokePreToolCall("terminal", "ls -la");
        assertThat(response.blocked()).isFalse();
    }

    @Test
    void invokePreToolCall_hookBlocks_returnsBlockResponse(@TempDir Path tempDir) throws Exception {
        // Create a script that outputs a block decision
        Path script = tempDir.resolve("block_hook.sh");
        Files.writeString(script, """
            #!/bin/bash
            echo '{"action": "block", "message": "Forbidden command"}'
            """);
        script.toFile().setExecutable(true);

        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", script.toString(), "matcher", "terminal")
        ));
        mgr.registerFromConfig(config);

        HookResponse response = mgr.invokePreToolCall("terminal", "rm -rf /");
        assertThat(response.blocked()).isTrue();
        assertThat(response.message()).isEqualTo("Forbidden command");
    }

    @Test
    void invokePreToolCall_claudeCodeStyleBlock_accepted(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("block_hook.sh");
        Files.writeString(script, """
            #!/bin/bash
            echo '{"decision": "block", "reason": "Dangerous"}'
            """);
        script.toFile().setExecutable(true);

        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", script.toString(), "matcher", "terminal")
        ));
        mgr.registerFromConfig(config);

        HookResponse response = mgr.invokePreToolCall("terminal", "rm -rf /");
        assertThat(response.blocked()).isTrue();
        assertThat(response.message()).isEqualTo("Dangerous");
    }

    @Test
    void invokePreToolCall_matcherMismatch_hookNotInvoked(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("block_hook.sh");
        Files.writeString(script, """
            #!/bin/bash
            echo '{"action": "block", "message": "Should not reach here"}'
            """);
        script.toFile().setExecutable(true);

        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", script.toString(), "matcher", "file")
        ));
        mgr.registerFromConfig(config);

        // Invoke with "terminal" — matcher is "file", so hook should not fire
        HookResponse response = mgr.invokePreToolCall("terminal", "ls");
        assertThat(response.blocked()).isFalse();
    }

    @Test
    void invokePreToolCall_invalidJson_returnsNoop(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("bad_hook.sh");
        Files.writeString(script, """
            #!/bin/bash
            echo 'not json at all'
            """);
        script.toFile().setExecutable(true);

        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", script.toString(), "matcher", "terminal")
        ));
        mgr.registerFromConfig(config);

        HookResponse response = mgr.invokePreToolCall("terminal", "ls");
        assertThat(response.blocked()).isFalse();
    }

    @Test
    void invokePreToolCall_emptyOutput_returnsNoop(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", "/bin/true", "matcher", "terminal")
        ));
        mgr.registerFromConfig(config);

        HookResponse response = mgr.invokePreToolCall("terminal", "ls");
        assertThat(response.blocked()).isFalse();
    }

    @Test
    void invokePreToolCall_commandNotFound_returnsNoop(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", "/nonexistent/hook.sh", "matcher", "terminal")
        ));
        mgr.registerFromConfig(config);

        HookResponse response = mgr.invokePreToolCall("terminal", "ls");
        assertThat(response.blocked()).isFalse();
    }

    @Test
    void invokePostToolCall_doesNotThrow(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("post_tool_call", List.of(
            Map.of("command", "/bin/true", "matcher", "terminal")
        ));
        mgr.registerFromConfig(config);

        // Should not throw
        mgr.invokePostToolCall("terminal", "ls", 0, "output");
    }

    // ─── Allowlist tests ───

    @Test
    void allowlist_autoAccepted_withAcceptHooks(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", "/bin/true")
        ));
        List<ShellHookSpec> registered = mgr.registerFromConfig(config);
        assertThat(registered).hasSize(1);

        // Allowlist file should be created
        Path allowlist = tempDir.resolve("shell-hooks-allowlist.json");
        assertThat(allowlist).exists();
    }

    @Test
    void allowlist_notCreated_withoutAcceptHooks(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), false);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", "/bin/true")
        ));
        // Without acceptHooks and without TTY, registration should be skipped
        List<ShellHookSpec> registered = mgr.registerFromConfig(config);
        assertThat(registered).isEmpty();

        // Allowlist file should NOT be created
        Path allowlist = tempDir.resolve("shell-hooks-allowlist.json");
        assertThat(allowlist).doesNotExist();
    }

    @Test
    void allowlist_existingEntry_hookRegisteredWithoutPrompt(@TempDir Path tempDir) throws Exception {
        // Pre-create allowlist with an entry
        Path allowlist = tempDir.resolve("shell-hooks-allowlist.json");
        Files.writeString(allowlist, """
            {
              "approvals": [
                {
                  "event": "pre_tool_call",
                  "command": "/bin/true",
                  "approved_at": "2024-01-01T00:00:00Z"
                }
              ]
            }
            """);

        // Even without acceptHooks, the hook should be registered because it's allowlisted
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), false);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", "/bin/true")
        ));
        List<ShellHookSpec> registered = mgr.registerFromConfig(config);
        assertThat(registered).hasSize(1);
    }

    // ─── Introspection tests ───

    @Test
    void getRegisteredHooks_returnsAllRegistered(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of(
            "pre_tool_call", List.of(Map.of("command", "/bin/true", "matcher", "terminal")),
            "post_tool_call", List.of(Map.of("command", "/bin/true", "matcher", "file"))
        );
        mgr.registerFromConfig(config);
        assertThat(mgr.getRegisteredHooks()).hasSize(2);
    }

    @Test
    void getHooksForEvent_returnsCorrectHooks(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of(
            "pre_tool_call", List.of(
                Map.of("command", "/bin/true", "matcher", "terminal"),
                Map.of("command", "/bin/false", "matcher", "file")
            )
        );
        mgr.registerFromConfig(config);
        List<ShellHookSpec> preHooks = mgr.getHooksForEvent("pre_tool_call");
        assertThat(preHooks).hasSize(2);
        assertThat(mgr.getHooksForEvent("post_tool_call")).isEmpty();
    }

    @Test
    void reset_clearsAllHooks(@TempDir Path tempDir) {
        ShellHookManager mgr = new ShellHookManager(tempDir.toString(), true);
        var config = Map.of("pre_tool_call", List.of(
            Map.of("command", "/bin/true")
        ));
        mgr.registerFromConfig(config);
        assertThat(mgr.getRegisteredHooks()).hasSize(1);

        mgr.reset();
        assertThat(mgr.getRegisteredHooks()).isEmpty();
    }
}