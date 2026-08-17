package com.azhukov.agent.tools.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CommandGuard UX improvement:
 * - p12: blocked-command recovery — when a command is blocked, suggest alternatives.
 */
class CommandGuardSuggestionTest {

    private CommandGuard guard() { return new CommandGuard(null, true); }

    // ── rm -rf suggestions ───

    @Test
    void rmRfRootSuggestsTrashOrMv() {
        String result = guard().check("rm -rf /");
        assertThat(result).isNotNull();
        assertThat(result).contains("Blocked");
        assertThat(result).contains("trash");
        assertThat(result).contains("mv");
    }

    @Test
    void rmRfHomeSuggestsTrashOrMv() {
        String result = guard().check("rm -rf ~/");
        assertThat(result).isNotNull();
        assertThat(result).contains("trash");
    }

    @Test
    void rmRecursiveForceSuggestsAlternative() {
        String result = guard().check("rm --recursive --force /");
        assertThat(result).isNotNull();
        assertThat(result).contains("trash");
    }

    // ── sudo suggestions ───

    @Test
    void sudoSuggestsRunningWithoutSudo() {
        String result = guard().check("sudo echo hello");
        assertThat(result).isNotNull();
        assertThat(result).contains("sudo");
    }

    // ── mkfs suggestions ───

    @Test
    void mkfsSuggestsLoopbackDevice() {
        String result = guard().check("mkfs.ext4 /dev/sda1");
        assertThat(result).isNotNull();
        assertThat(result).contains("loopback");
    }

    // ── dd suggestions ───

    @Test
    void ddIfBlockDeviceSuggestsFile() {
        String result = guard().check("dd if=/dev/sda of=/tmp/backup");
        assertThat(result).isNotNull();
        assertThat(result).contains("regular file");
    }

    @Test
    void ddOfBlockDeviceSuggestsFile() {
        String result = guard().check("dd if=/dev/zero of=/dev/sda bs=1M");
        assertThat(result).isNotNull();
        assertThat(result).contains("regular file");
    }

    // ── shutdown/reboot suggestions ───

    @Test
    void shutdownSuggestsSafetyBlock() {
        String result = guard().check("shutdown -h now");
        assertThat(result).isNotNull();
        assertThat(result).contains("system power commands");
    }

    @Test
    void rebootSuggestsSafetyBlock() {
        String result = guard().check("reboot");
        assertThat(result).isNotNull();
        assertThat(result).contains("system power commands");
    }

    // ── kill -9 -1 suggestions ───

    @Test
    void killAllSuggestsSpecificPids() {
        String result = guard().check("kill -9 -1");
        assertThat(result).isNotNull();
        assertThat(result).contains("specific process IDs");
    }

    // ── iptables -F suggestions ───

    @Test
    void iptablesFlushSuggestsSavingRules() {
        String result = guard().check("iptables -F");
        assertThat(result).isNotNull();
        assertThat(result).contains("iptables-save");
    }

    // ── curl/wget suggestions (when blocked by user patterns) ───

    @Test
    void curlBlockedByUserPatternSuggestsWebSearch() {
        CommandGuard g = new CommandGuard(List.of("\\bcurl\\b"), true);
        String result = g.check("curl http://example.com");
        assertThat(result).isNotNull();
        assertThat(result).contains("web_search");
    }

    @Test
    void wgetBlockedByUserPatternSuggestsWebSearch() {
        CommandGuard g = new CommandGuard(List.of("\\bwget\\b"), true);
        String result = g.check("wget http://example.com");
        assertThat(result).isNotNull();
        assertThat(result).contains("web_search");
    }

    // ── Safe commands don't get suggestions ───

    @Test
    void safeCommandReturnsNullNoSuggestion() {
        assertThat(guard().check("echo hello")).isNull();
        assertThat(guard().check("ls -la")).isNull();
        assertThat(guard().check("git status")).isNull();
    }

    // ── Suggestion format ───

    @Test
    void suggestionIsAppendedWithEmDash() {
        String result = guard().check("rm -rf /");
        assertThat(result).isNotNull();
        assertThat(result).contains(" — ");
    }
}