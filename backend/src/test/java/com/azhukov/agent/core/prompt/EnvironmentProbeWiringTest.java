package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.context.CodingContextDetector;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.AgentConstants;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EnvironmentProbe wiring (Hermes system_prompt.py:576-590): the probe line
 * rides in the context tier when the toolchain is non-default, and NOTHING
 * is appended when the environment is clean.
 */
class EnvironmentProbeWiringTest {

    private DefaultPromptBuilder builderWithProbe(EnvironmentProbe probe) {
        AgentProperties properties = new AgentProperties();
        properties.getCodingContext().setEnabled(false); // snapshot bean is null in unit tests
        PromptCacheTracker tracker = new PromptCacheTracker(properties);
        return new DefaultPromptBuilder(
            properties, mock(ToolRegistry.class), mock(AgentConstants.class),
            tracker, mock(CodingContextDetector.class),
            mock(MemoryProvider.class), mock(SkillManager.class), null, probe);
    }

    @Test
    void probeLineIncludedWhenPresent() {
        EnvironmentProbe probe = mock(EnvironmentProbe.class);
        when(probe.getProbeLine()).thenReturn("Python toolchain: PEP-668 externally-managed; use pip --break-system-packages or uv");
        Message msg = builderWithProbe(probe).buildSystemMessage(Session.create("u", "p", "m"));
        assertThat(msg.content()).contains("PEP-668 externally-managed");
    }

    @Test
    void nothingAppendedWhenEnvironmentClean() {
        EnvironmentProbe probe = mock(EnvironmentProbe.class);
        when(probe.getProbeLine()).thenReturn("");
        Message clean = builderWithProbe(probe).buildSystemMessage(Session.create("u", "p", "m"));
        // Clean environment => NO probe residue in the prompt (the empty line
        // must not be appended either — zero token cost, Hermes parity).
        assertThat(clean.content()).doesNotContain("PEP-668");
        assertThat(clean.content()).doesNotContain("externally-managed");
    }

    @Test
    void probeFailureNeverBlocksPromptBuild() {
        EnvironmentProbe probe = mock(EnvironmentProbe.class);
        when(probe.getProbeLine()).thenThrow(new RuntimeException("probe crashed"));
        Message msg = builderWithProbe(probe).buildSystemMessage(Session.create("u", "p", "m"));
        assertThat(msg).isNotNull();
        assertThat(msg.content()).isNotBlank();
    }
}
