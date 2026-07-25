package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class CommandApprovalManager {

    private static final Set<String> DANGEROUS_PATTERNS = Set.of(
        "rm -rf /", "rm -rf /*", "mkfs", "dd if=/dev/zero", "chmod 777 /",
        ":(){ :|: & };:", "> /dev/sda", "shutdown", "reboot", "poweroff",
        "halt", "init 0", "format", "del /f /s /q C:", "rd /s /q C:"
    );

    private final AgentProperties properties;
    private final Set<String> sessionAllowlist = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public CommandApprovalManager(AgentProperties properties) {
        this.properties = properties;
    }

    public ApprovalStatus requireApproval(String command) {
        if (!properties.getSecurity().isApprovalsEnabled()) {
            return ApprovalStatus.YOLO;
        }
        if (command == null || command.isBlank()) {
            return ApprovalStatus.YOLO;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        for (String allowed : properties.getSecurity().getBlockedCommands()) {
            if (normalized.contains(allowed.toLowerCase(Locale.ROOT))) {
                return ApprovalStatus.BLOCKED;
            }
        }
        for (String allowed : sessionAllowlist) {
            if (normalized.contains(allowed.toLowerCase(Locale.ROOT))) {
                return ApprovalStatus.ALLOWED;
            }
        }
        for (String allowed : properties.getTerminal().getRequireApprovalCommands()) {
            if (normalized.contains(allowed.toLowerCase(Locale.ROOT))) {
                return ApprovalStatus.REQUIRED;
            }
        }
        for (String dangerous : DANGEROUS_PATTERNS) {
            if (normalized.contains(dangerous.toLowerCase(Locale.ROOT))) {
                return ApprovalStatus.REQUIRED;
            }
        }
        // Read-only commands do not require approval.
        if (isReadOnly(normalized)) {
            return ApprovalStatus.YOLO;
        }
        return ApprovalStatus.YOLO;
    }

    public void allowForSession(String commandPattern) {
        if (commandPattern != null && !commandPattern.isBlank()) {
            sessionAllowlist.add(commandPattern.trim().toLowerCase(Locale.ROOT));
        }
    }

    public void clearSessionAllowlist() {
        sessionAllowlist.clear();
    }

    private boolean isReadOnly(String normalized) {
        List<String> readOnlyPrefixes = List.of("ls", "cat", "echo", "pwd", "whoami", "uname", "ps", "grep", "find", "head", "tail", "wc", "sort", "uniq", "awk", "sed", "file", "stat", "which", "id", "date", "env");
        for (String prefix : readOnlyPrefixes) {
            if (normalized.startsWith(prefix + " ") || normalized.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    public enum ApprovalStatus {
        YOLO,
        ALLOWED,
        REQUIRED,
        BLOCKED
    }
}
