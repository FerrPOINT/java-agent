package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Feature 7: Configurable tool-output limits.
 *
 * Mirrors Hermes tools/tool_output_limits.py — get_tool_output_limits().
 * Centralizes truncation thresholds behind config so users can tune them.
 * Defaults match pre-existing hardcoded values (behavior-preserving).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolOutputLimitsConfig {

    private final AgentProperties properties;

    /**
     * Terminal output max chars (default 50000).
     */
    public int getTerminalMaxChars() {
        int v = properties.getToolOutput().getTerminalMaxChars();
        return v > 0 ? v : 50000;
    }

    /**
     * Read file max lines (default 2000).
     */
    public int getReadFileMaxLines() {
        int v = properties.getToolOutput().getReadFileMaxLines();
        return v > 0 ? v : 2000;
    }

    /**
     * Per-line max chars (default 2000).
     */
    public int getPerLineMaxChars() {
        int v = properties.getToolOutput().getPerLineMaxChars();
        return v > 0 ? v : 2000;
    }

    /**
     * Web extract max chars (default 5000).
     */
    public int getWebExtractMaxChars() {
        int v = properties.getToolOutput().getWebExtractMaxChars();
        return v > 0 ? v : 5000;
    }

    /**
     * Persist threshold in bytes (default 51200 = 50KB).
     */
    public int getPersistThresholdBytes() {
        int v = properties.getToolOutput().getPersistThresholdBytes();
        return v > 0 ? v : 51200;
    }

    /**
     * Per-turn aggregate budget in bytes (default 204800 = 200KB).
     */
    public int getTurnBudgetBytes() {
        int v = properties.getToolOutput().getTurnBudgetBytes();
        return v > 0 ? v : 204800;
    }
}