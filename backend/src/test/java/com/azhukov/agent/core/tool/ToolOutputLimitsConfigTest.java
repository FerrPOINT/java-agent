package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 7: Configurable tool-output limits test.
 * Verifies config values override defaults.
 */
class ToolOutputLimitsConfigTest {

    @Test
    void defaultsAreAppliedWhenConfigNotSet() {
        AgentProperties props = new AgentProperties();
        ToolOutputLimitsConfig config = new ToolOutputLimitsConfig(props);

        assertThat(config.getTerminalMaxChars()).isEqualTo(50000);
        assertThat(config.getReadFileMaxLines()).isEqualTo(2000);
        assertThat(config.getPerLineMaxChars()).isEqualTo(2000);
        assertThat(config.getWebExtractMaxChars()).isEqualTo(5000);
        assertThat(config.getPersistThresholdBytes()).isEqualTo(51200);
        assertThat(config.getTurnBudgetBytes()).isEqualTo(204800);
    }

    @Test
    void customTerminalMaxChars() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setTerminalMaxChars(100000);
        ToolOutputLimitsConfig config = new ToolOutputLimitsConfig(props);

        assertThat(config.getTerminalMaxChars()).isEqualTo(100000);
    }

    @Test
    void customReadFileMaxLines() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setReadFileMaxLines(5000);
        ToolOutputLimitsConfig config = new ToolOutputLimitsConfig(props);

        assertThat(config.getReadFileMaxLines()).isEqualTo(5000);
    }

    @Test
    void customPerLineMaxChars() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setPerLineMaxChars(4000);
        ToolOutputLimitsConfig config = new ToolOutputLimitsConfig(props);

        assertThat(config.getPerLineMaxChars()).isEqualTo(4000);
    }

    @Test
    void customWebExtractMaxChars() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setWebExtractMaxChars(8000);
        ToolOutputLimitsConfig config = new ToolOutputLimitsConfig(props);

        assertThat(config.getWebExtractMaxChars()).isEqualTo(8000);
    }

    @Test
    void zeroValueFallsBackToDefault() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setTerminalMaxChars(0);
        ToolOutputLimitsConfig config = new ToolOutputLimitsConfig(props);

        assertThat(config.getTerminalMaxChars()).isEqualTo(50000);
    }

    @Test
    void negativeValueFallsBackToDefault() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setReadFileMaxLines(-1);
        ToolOutputLimitsConfig config = new ToolOutputLimitsConfig(props);

        assertThat(config.getReadFileMaxLines()).isEqualTo(2000);
    }
}