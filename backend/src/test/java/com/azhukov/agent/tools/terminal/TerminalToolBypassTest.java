package com.azhukov.agent.tools.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalToolBypassTest {

    private CommandGuard guard() { return new CommandGuard(List.of(), true); }
    private CommandGuard guardNoSudo() { return new CommandGuard(List.of(), false); }
    private CommandGuard guardWithCustom(List<String> extra) { return new CommandGuard(extra, true); }

    @Test void blocksRmRfRoot() { assertThat(guard().check("rm -rf /")).contains("Blocked"); }
    @Test void blocksRmFrRoot() { assertThat(guard().check("rm -fr /")).contains("Blocked"); }
    @Test void blocksRmRfRootWithExtraWhitespace() { assertThat(guard().check("rm   -rf   /")).contains("Blocked"); }
    @Test void blocksRmRfStar() { assertThat(guard().check("rm -rf /*")).contains("Blocked"); }
    @Test void blocksRmRfTilde() { assertThat(guard().check("rm -rf ~/")).contains("Blocked"); }
    @Test void blocksRmForceRecursiveRoot() { assertThat(guard().check("rm --force -r /")).contains("Blocked"); }
    @Test void blocksRmRecursiveForceRoot() { assertThat(guard().check("rm --recursive --force /")).contains("Blocked"); }
    @Test void blocksRmForceRecursiveTilde() { assertThat(guard().check("rm --force --recursive ~/")).contains("Blocked"); }
    @Test void blocksRmRfRootWithChainedCommand() { assertThat(guard().check("echo hi; rm -rf /")).contains("Blocked"); }
    @Test void blocksRmRfRootWithPipe() { assertThat(guard().check("echo hi | rm -rf /")).contains("Blocked"); }
    @Test void blocksRmRfRootWithAmpersand() { assertThat(guard().check("echo hi && rm -rf /")).contains("Blocked"); }

    @Test void blocksSudoPrefix() { assertThat(guard().check("sudo rm /tmp/test")).contains("sudo"); }
    @Test void blocksSudoWithEnvPrefix() { assertThat(guard().check("FOO=bar sudo echo hi")).contains("sudo"); }
    @Test void blocksSudoAlone() { assertThat(guard().check("sudo")).contains("sudo"); }
    @Test void allowsSudoWhenDisabled() { assertThat(guardNoSudo().check("sudo echo hello")).isNull(); }
    @Test void doesNotBlockSudoInEcho() { assertThat(guard().check("echo sudo")).isNull(); }
    @Test void doesNotBlockSudoInPath() {
        String result = guard().check("cat /etc/sudoers");
        if (result != null) { assertThat(result).doesNotContain("sudo"); }
    }

    @Test void blocksMkfs() { assertThat(guard().check("mkfs.ext4 /dev/sda1")).contains("Blocked"); }
    @Test void blocksMkfsBtrfs() { assertThat(guard().check("mkfs.btrfs /dev/sda1")).contains("Blocked"); }
    @Test void blocksMkfsXfs() { assertThat(guard().check("mkfs.xfs /dev/nvme0n1")).contains("Blocked"); }

    @Test void blocksDdIfBlockDevice() { assertThat(guard().check("dd if=/dev/sda of=/tmp/backup")).contains("Blocked"); }
    @Test void blocksDdOfDevSda() { assertThat(guard().check("dd if=/dev/urandom of=/dev/sda bs=1M")).contains("Blocked"); }
    @Test void blocksDdOfDevNvme() { assertThat(guard().check("dd if=/dev/zero of=/dev/nvme0n1")).contains("Blocked"); }
    @Test void blocksRedirectToBlockDevice() { assertThat(guard().check("cat /dev/urandom > /dev/sda")).contains("Blocked"); }

    @Test void blocksForkBomb() { assertThat(guard().check(":(){ :|:& };:")).contains("Blocked"); }
    @Test void blocksForkBombWithSpaces() { assertThat(guard().check(": () { : | : & } ; :")).contains("Blocked"); }

    @Test void blocksShutdown() { assertThat(guard().check("shutdown -h now")).contains("Blocked"); }
    @Test void blocksReboot() { assertThat(guard().check("reboot")).contains("Blocked"); }
    @Test void blocksHalt() { assertThat(guard().check("halt")).contains("Blocked"); }
    @Test void blocksPoweroff() { assertThat(guard().check("poweroff")).contains("Blocked"); }

    @Test void blocksOverwriteEtcPasswd() { assertThat(guard().check("echo x > /etc/passwd")).contains("Blocked"); }
    @Test void blocksOverwriteEtcShadow() { assertThat(guard().check("echo x > /etc/shadow")).contains("Blocked"); }
    @Test void blocksOverwriteEtcSudoers() { assertThat(guard().check("echo x > /etc/sudoers")).contains("Blocked"); }

    @Test void blocksKillAll() { assertThat(guard().check("kill -9 -1")).contains("Blocked"); }
    @Test void blocksIptablesFlush() { assertThat(guard().check("iptables -F")).contains("Blocked"); }

    @Test void blocksCustomPattern() {
        CommandGuard g = guardWithCustom(List.of("\\bmydangerous\\b"));
        assertThat(g.check("mydangerous arg")).contains("Blocked");
    }
    @Test void customPatternDoesNotBlockSafeCommands() {
        CommandGuard g = guardWithCustom(List.of("\\bmydangerous\\b"));
        assertThat(g.check("echo hello")).isNull();
    }
    @Test void multipleCustomPatterns() {
        CommandGuard g = guardWithCustom(List.of("\\bmydangerous\\b", "\\bforbidden\\b"));
        assertThat(g.check("mydangerous arg")).contains("Blocked");
        assertThat(g.check("forbidden run")).contains("Blocked");
        assertThat(g.check("echo safe")).isNull();
    }

    @Test void allowsEcho() { assertThat(guard().check("echo hello")).isNull(); }
    @Test void allowsLs() { assertThat(guard().check("ls -la /tmp")).isNull(); }
    @Test void allowsRmInTmp() { assertThat(guard().check("rm -f /tmp/nonexistent_test_file_xyz123")).isNull(); }
    @Test void allowsRmRfSpecificDir() { assertThat(guard().check("rm -rf /tmp/build")).isNull(); }
    @Test void allowsDdToRegularFile() { assertThat(guard().check("dd if=/dev/zero of=/tmp/testfile bs=1M count=1")).isNull(); }
    @Test void allowsNullAndBlank() {
        assertThat(guard().check(null)).isNull();
        assertThat(guard().check("")).isNull();
        assertThat(guard().check("   ")).isNull();
    }

    @Test void normaliseCollapsesWhitespace() { assertThat(CommandGuard.normalise("rm   -rf   /")).isEqualTo("rm -rf /"); }
    @Test void normaliseTrims() { assertThat(CommandGuard.normalise("  echo hi  ")).isEqualTo("echo hi"); }
    @Test void startsWithSudoDetectsPlainSudo() { assertThat(CommandGuard.startsWithSudo("sudo echo hi")).isTrue(); }
    @Test void startsWithSudoDetectsEnvPrefix() { assertThat(CommandGuard.startsWithSudo("FOO=bar sudo echo hi")).isTrue(); }
    @Test void startsWithSudoDoesNotMatchEcho() { assertThat(CommandGuard.startsWithSudo("echo sudo")).isFalse(); }
    @Test void startsWithSudoDoesNotMatchSudoInPath() { assertThat(CommandGuard.startsWithSudo("cat /sudoers")).isFalse(); }
}