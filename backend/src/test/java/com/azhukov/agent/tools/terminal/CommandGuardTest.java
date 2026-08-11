package com.azhukov.agent.tools.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CommandGuard} — regex-based dangerous command detection
 * and shell hook integration.
 */
class CommandGuardTest {

    // ─── Basic pattern matching tests ───

    @Test
    void check_nullCommand_returnsNull() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check(null)).isNull();
    }

    @Test
    void check_blankCommand_returnsNull() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("  ")).isNull();
    }

    @Test
    void check_safeCommand_returnsNull() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("echo hello")).isNull();
        assertThat(guard.check("ls -la")).isNull();
        assertThat(guard.check("git status")).isNull();
    }

    // ─── sudo blocking ───

    @Test
    void check_sudoBlocked_whenBlockSudoTrue() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("sudo apt-get install something")).isNotNull();
        assertThat(guard.check("sudo rm file")).isNotNull();
    }

    @Test
    void check_sudoAllowed_whenBlockSudoFalse() {
        CommandGuard guard = new CommandGuard(null, false);
        assertThat(guard.check("sudo apt-get install something")).isNull();
    }

    @Test
    void check_sudoWithEnvVar_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        // VAR=val sudo ... should still be blocked
        assertThat(guard.check("VAR=val sudo ls")).isNotNull();
    }

    // ─── rm -rf tests ───

    @Test
    void check_rmRfRoot_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("rm -rf /")).isNotNull();
    }

    @Test
    void check_rmFrRoot_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("rm -fr /")).isNotNull();
    }

    @Test
    void check_rmRfRootStar_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("rm -rf /*")).isNotNull();
    }

    @Test
    void check_rmRfHome_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("rm -rf ~/")).isNotNull();
    }

    @Test
    void check_rmRfHomeStar_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("rm -rf ~/*")).isNotNull();
    }

    @Test
    void check_rmRfSpecificPath_notBlocked() {
        CommandGuard guard = new CommandGuard(null, true);
        // rm -rf /tmp/specific-dir should NOT be blocked (it's not root or home)
        assertThat(guard.check("rm -rf /tmp/specific-dir")).isNull();
    }

    @Test
    void check_rmSeparateFlags_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("rm -r -f /")).isNotNull();
        assertThat(guard.check("rm -f -r /")).isNotNull();
    }

    @Test
    void check_rmRecursiveForce_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("rm --recursive --force /")).isNotNull();
        assertThat(guard.check("rm --force --recursive /")).isNotNull();
    }

    @Test
    void check_rmRfWithExtraWhitespace_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        // Extra whitespace should not bypass detection
        assertThat(guard.check("rm    -rf    /")).isNotNull();
    }

    // ─── Other dangerous commands ───

    @Test
    void check_mkfs_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("mkfs.ext4 /dev/sda1")).isNotNull();
    }

    @Test
    void check_ddIfBlockDevice_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("dd if=/dev/sda of=/tmp/backup")).isNotNull();
    }

    @Test
    void check_ddIfDevZeroToRegularFile_allowed() {
        CommandGuard guard = new CommandGuard(null, true);
        // dd if=/dev/zero to a regular file is legitimate (creating test files)
        assertThat(guard.check("dd if=/dev/zero of=/tmp/testfile bs=1M count=1")).isNull();
    }

    @Test
    void check_ddOfDevSd_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("dd if=/dev/zero of=/dev/sda")).isNotNull();
    }

    @Test
    void check_forkBomb_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check(":(){ :|:& };:")).isNotNull();
    }

    @Test
    void check_redirectToBlockDevice_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("echo bad > /dev/sda")).isNotNull();
    }

    @Test
    void check_shutdown_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("shutdown -h now")).isNotNull();
    }

    @Test
    void check_reboot_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("reboot")).isNotNull();
    }

    @Test
    void check_overwriteEtcPasswd_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("echo bad > /etc/passwd")).isNotNull();
    }

    @Test
    void check_killAllProcesses_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("kill -9 -1")).isNotNull();
    }

    @Test
    void check_iptablesFlush_blocked() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.check("iptables -F")).isNotNull();
    }

    // ─── User-supplied patterns ───

    @Test
    void check_userPattern_blocked() {
        CommandGuard guard = new CommandGuard(List.of("forbidden_command"), true);
        assertThat(guard.check("forbidden_command arg")).isNotNull();
    }

    @Test
    void check_userPatternCaseInsensitive_blocked() {
        CommandGuard guard = new CommandGuard(List.of("FORBIDDEN"), true);
        assertThat(guard.check("forbidden_command")).isNotNull();
    }

    @Test
    void check_blankUserPattern_ignored() {
        CommandGuard guard = new CommandGuard(List.of("  "), true);
        // Blank pattern should be ignored, not cause errors
        assertThat(guard.check("echo hello")).isNull();
    }

    // ─── Normalisation tests ───

    @Test
    void normalise_collapsesWhitespace() {
        assertThat(CommandGuard.normalise("  echo   hello  ")).isEqualTo("echo hello");
    }

    @Test
    void normalise_handlesTabs() {
        assertThat(CommandGuard.normalise("echo\thello")).isEqualTo("echo hello");
    }

    @Test
    void startsWithSudo_simpleSudo() {
        assertThat(CommandGuard.startsWithSudo("sudo ls")).isTrue();
    }

    @Test
    void startsWithSudo_sudoWithEnvVar() {
        assertThat(CommandGuard.startsWithSudo("VAR=val sudo ls")).isTrue();
    }

    @Test
    void startsWithSudo_notSudo() {
        assertThat(CommandGuard.startsWithSudo("echo sudo")).isFalse();
    }

    // ─── Shell hook integration tests (P1-11) ───

    @Test
    void check_withShellHookManager_noHooks_returnsNull() {
        ShellHookManager hookMgr = new ShellHookManager("/tmp/test-hermes", true);
        CommandGuard guard = new CommandGuard(null, true, hookMgr);
        assertThat(guard.check("echo hello")).isNull();
    }

    @Test
    void getShellHookManager_returnsManager() {
        ShellHookManager hookMgr = new ShellHookManager("/tmp/test-hermes", true);
        CommandGuard guard = new CommandGuard(null, true, hookMgr);
        assertThat(guard.getShellHookManager()).isSameAs(hookMgr);
    }

    @Test
    void getShellHookManager_nullWhenNotProvided() {
        CommandGuard guard = new CommandGuard(null, true);
        assertThat(guard.getShellHookManager()).isNull();
    }

    @Test
    void notifyPostExecution_doesNotThrowWithoutHookManager() {
        CommandGuard guard = new CommandGuard(null, true);
        // Should not throw
        guard.notifyPostExecution("echo hello", 0, "hello\n");
    }

    @Test
    void notifyPostExecution_doesNotThrowWithHookManager() {
        ShellHookManager hookMgr = new ShellHookManager("/tmp/test-hermes", true);
        CommandGuard guard = new CommandGuard(null, true, hookMgr);
        // Should not throw
        guard.notifyPostExecution("echo hello", 0, "hello\n");
    }
}