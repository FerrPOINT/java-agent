package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.state.DefaultAgentConstants;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P-04: prompt guidance must match the request's effective API tool list. */
class EffectiveToolPromptTest {

    private DefaultPromptBuilder builderWithGlobalTools() {
        AgentProperties props = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of("memory", "skills", "web", "productivity"));
        when(registry.getDefinitions()).thenReturn(List.of());
        return new DefaultPromptBuilder(props, registry, new DefaultAgentConstants());
    }

    @Test
    void disabledMemoryDoesNotInjectMemoryGuidance() {
        DefaultPromptBuilder builder = builderWithGlobalTools();
        Message prompt = builder.buildSystemMessageForTools(
            Session.create("u", "p", "m"), Set.of("web_search", "skill_view"));
        assertThat(prompt.content()).doesNotContain(DefaultPromptBuilder.MEMORY_GUIDANCE);
    }

    @Test
    void disabledSkillsDoNotInjectSkillsGuidanceOrAgentSkillInstruction() {
        DefaultPromptBuilder builder = builderWithGlobalTools();
        Message prompt = builder.buildSystemMessageForTools(
            Session.create("u", "p", "m"), Set.of("memory", "web_search"));
        assertThat(prompt.content()).doesNotContain(DefaultPromptBuilder.SKILLS_GUIDANCE);
        assertThat(prompt.content()).doesNotContain("Load a relevant bundled skill with skill_view");
    }

    @Test
    void skillsGuidanceAppearsOnlyWhenSkillToolIsEffective() {
        DefaultPromptBuilder builder = builderWithGlobalTools();
        Message prompt = builder.buildSystemMessageForTools(
            Session.create("u", "p", "m"), Set.of("skill_view"));
        assertThat(prompt.content()).contains(DefaultPromptBuilder.SKILLS_GUIDANCE);
        assertThat(prompt.content()).contains("Load a relevant bundled skill with skill_view");
    }

    @Test
    void requestScopedToolSetDoesNotLeakToFollowingPrompt() {
        DefaultPromptBuilder builder = builderWithGlobalTools();
        builder.buildSystemMessageForTools(Session.create("u", "p", "m"), Set.of("skill_view"));
        Message next = builder.buildSystemMessageForTools(
            Session.create("u", "p", "m"), Set.of("memory"));
        assertThat(next.content()).doesNotContain(DefaultPromptBuilder.SKILLS_GUIDANCE);
        assertThat(next.content()).contains(DefaultPromptBuilder.MEMORY_GUIDANCE);
    }

    @Test
    void scopedToolSetIsSeparatedInPromptCacheSessionMetadata() {
        Session session = Session.create("u", "p", "m");
        Session scoped = session.withMetadata("effectiveToolNames", "memory,web_search");
        assertThat(scoped.getMetadata("effectiveToolNames")).isEqualTo("memory,web_search");
        assertThat(session.getMetadata("effectiveToolNames")).isNull();
    }
}
