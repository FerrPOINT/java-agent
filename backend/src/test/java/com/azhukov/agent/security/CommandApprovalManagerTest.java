package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandApprovalManagerTest {

    private AgentProperties defaultProps() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setApprovalsEnabled(true);
        return p;
    }

    @Test
    void readOnlyCommandsAreYolo() {
        CommandApprovalManager m = new CommandApprovalManager(defaultProps());
        assertThat(m.requireApproval("ls /tmp")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
        assertThat(m.requireApproval("cat file.txt")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void dangerousCommandsRequireApproval() {
        CommandApprovalManager m = new CommandApprovalManager(defaultProps());
        assertThat(m.requireApproval("rm -rf /")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
        assertThat(m.requireApproval("shutdown")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void configuredBlockedCommandIsBlocked() {
        AgentProperties p = defaultProps();
        p.getSecurity().setBlockedCommands(List.of("curl"));
        CommandApprovalManager m = new CommandApprovalManager(p);
        assertThat(m.requireApproval("curl http://x")).isEqualTo(CommandApprovalManager.ApprovalStatus.BLOCKED);
    }

    @Test
    void configuredApprovalCommandRequiresApproval() {
        AgentProperties p = defaultProps();
        p.getTerminal().getRequireApprovalCommands().add("docker");
        CommandApprovalManager m = new CommandApprovalManager(p);
        assertThat(m.requireApproval("docker ps")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }

    @Test
    void sessionAllowlistGrantsAllowed() {
        CommandApprovalManager m = new CommandApprovalManager(defaultProps());
        m.allowForSession("rm -rf /tmp/safe");
        assertThat(m.requireApproval("rm -rf /tmp/safe")).isEqualTo(CommandApprovalManager.ApprovalStatus.ALLOWED);
    }

    @Test
    void disabledApprovalsYolo() {
        AgentProperties p = defaultProps();
        p.getSecurity().setApprovalsEnabled(false);
        CommandApprovalManager m = new CommandApprovalManager(p);
        assertThat(m.requireApproval("rm -rf /")).isEqualTo(CommandApprovalManager.ApprovalStatus.YOLO);
    }

    @Test
    void clearSessionAllowlistRemovesEntry() {
        CommandApprovalManager m = new CommandApprovalManager(defaultProps());
        m.allowForSession("rm -rf /tmp/safe");
        m.clearSessionAllowlist();
        assertThat(m.requireApproval("rm -rf /tmp/safe")).isEqualTo(CommandApprovalManager.ApprovalStatus.REQUIRED);
    }
}
