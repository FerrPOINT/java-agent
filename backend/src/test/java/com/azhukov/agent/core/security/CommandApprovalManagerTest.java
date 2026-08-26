package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandApprovalManagerTest {

    private AgentProperties properties;
    private CommandApprovalManager manager;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        manager = new CommandApprovalManager(properties);
    }

    // ─── requireApproval: approvals disabled ───

    @Test
    void requireApproval_approvalsDisabled_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(false);
        assertThat(manager.requireApproval("rm -rf /tmp")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_approvalsDisabled_nullCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(false);
        assertThat(manager.requireApproval(null)).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    // ─── requireApproval: null/blank input ───

    @Test
    void requireApproval_nullCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval(null)).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_blankCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("   ")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_emptyCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    // ─── requireApproval: blocked commands ───

    @Test
    void requireApproval_blockedCommand_returnsBlocked() {
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().getBlockedCommands().add("rm -rf");
        assertThat(manager.requireApproval("rm -rf /home")).isEqualTo(CommandApprovalManager.ApprovalStatus.BLOCKED);
    }

    @Test
    void requireApproval_blockedCommandCaseInsensitive_returnsBlocked() {
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().getBlockedCommands().add("RM -RF");
        assertThat(manager.requireApproval("rm -rf /home")).isEqualTo(CommandApprovalManager.ApprovalStatus.BLOCKED);
    }

    // ─── requireApproval: session allowlist ───

    @Test
    void requireApproval_sessionAllowed_returnsAllowed() {
        properties.getSecurity().setApprovalsEnabled(true);
        manager.allowForSession("npm install");
        assertThat(manager.requireApproval("npm install express")).isEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
    }

    @Test
    void requireApproval_sessionAllowedCaseInsensitive_returnsAllowed() {
        properties.getSecurity().setApprovalsEnabled(true);
        manager.allowForSession("NPM INSTALL");
        assertThat(manager.requireApproval("npm install express")).isEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
    }

    @Test
    void requireApproval_sessionAllowedSubstringMatch_returnsAllowed() {
        properties.getSecurity().setApprovalsEnabled(true);
        manager.allowForSession("docker");
        assertThat(manager.requireApproval("docker build -t myapp .")).isEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
    }

    @Test
    void requireApproval_afterClearAllowlist_notAllowed() {
        properties.getSecurity().setApprovalsEnabled(true);
        manager.allowForSession("npm install");
        manager.clearSessionAllowlist();
        assertThat(manager.requireApproval("npm install express")).isNotEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
    }

    // ─── requireApproval: requireApprovalCommands ───

    @Test
    void requireApproval_configuredRequireApproval_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getTerminal().getRequireApprovalCommands().add("docker");
        assertThat(manager.requireApproval("docker build .")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_configuredRequireApprovalCaseInsensitive_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getTerminal().getRequireApprovalCommands().add("DOCKER");
        assertThat(manager.requireApproval("docker build .")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    // ─── requireApproval: dangerous patterns ───

    @Test
    void requireApproval_rmRfRoot_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("rm -rf /")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_rmRfRootStar_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("rm -rf /*")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_mkfs_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("mkfs.ext4 /dev/sda1")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_ddIfDevZero_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("dd if=/dev/zero of=/dev/sda")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_chmod777Root_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("chmod 777 /")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_forkBomb_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval(":(){ :|: & };:")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_writeDevSda_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("echo data > /dev/sda")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_shutdown_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("shutdown -h now")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_reboot_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("reboot")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_poweroff_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("poweroff")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_halt_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("halt")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_initZero_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("init 0")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_format_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("format C:")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_delWindowsRecursive_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("del /f /s /q C:")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void requireApproval_rdWindowsRecursive_returnsRequired() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("rd /s /q C:")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    // ─── requireApproval: read-only commands ───

    @Test
    void requireApproval_lsCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("ls -la")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_catCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("cat file.txt")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_echoCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("echo hello")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_pwdCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("pwd")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_grepCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("grep pattern file")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_findCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("find . -name '*.java'")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_whoamiCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("whoami")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_unameCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("uname -a")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_psCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("ps aux")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_headCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("head -20 file")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_tailCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("tail -f log")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_wcCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("wc -l file")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_dateCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("date")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_envCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("env")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_awkCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("awk '{print $1}' file")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_sedCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("sed 's/a/b/g' file")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_idCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("id")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_whichCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("which java")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_fileCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("file test.bin")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_statCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("stat file.txt")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_sortCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("sort file")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_uniqCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("uniq file")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    // ─── requireApproval: non-read-only, no match → YOLO ───

    @Test
    void requireApproval_unmatchedWriteCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("echo hello > file.txt")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void requireApproval_mkdirCommand_returnsYolo() {
        properties.getSecurity().setApprovalsEnabled(true);
        // mkdir is not in read-only list, not in dangerous patterns, not in requireApproval
        assertThat(manager.requireApproval("mkdir newdir")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    // ─── allowForSession ───

    @Test
    void allowForSession_nullInput_doesNotAdd() {
        manager.allowForSession(null);
        // Nothing was added — verify by checking that no command matches
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("some command")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void allowForSession_blankInput_doesNotAdd() {
        manager.allowForSession("   ");
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("some command")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void allowForSession_normalInput_addsToAllowlist() {
        manager.allowForSession("pip install");
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("pip install flask")).isEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
    }

    @Test
    void allowForSession_trimsInput() {
        manager.allowForSession("  npm install  ");
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("npm install express")).isEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
    }

    // ─── clearSessionAllowlist ───

    @Test
    void clearSessionAllowlist_removesAllEntries() {
        manager.allowForSession("npm install");
        manager.allowForSession("pip install");
        manager.clearSessionAllowlist();
        properties.getSecurity().setApprovalsEnabled(true);
        assertThat(manager.requireApproval("npm install express")).isNotEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
        assertThat(manager.requireApproval("pip install flask")).isNotEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
    }

    @Test
    void clearSessionAllowlist_emptyList_doesNotThrow() {
        manager.clearSessionAllowlist(); // no exception
    }

    // ─── priority: blocked > allowed > requireApproval > dangerous > readOnly ───

    @Test
    void requireApproval_blockedTakesPriorityOverAllowed() {
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().getBlockedCommands().add("rm");
        manager.allowForSession("rm");
        // Blocked should take priority over session allowlist
        assertThat(manager.requireApproval("rm something")).isEqualTo(CommandApprovalManager.ApprovalStatus.BLOCKED);
    }

    @Test
    void requireApproval_allowedTakesPriorityOverRequireApproval() {
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getTerminal().getRequireApprovalCommands().add("docker");
        manager.allowForSession("docker");
        // Session allowlist takes priority over requireApprovalCommands
        assertThat(manager.requireApproval("docker build .")).isEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
    }
}