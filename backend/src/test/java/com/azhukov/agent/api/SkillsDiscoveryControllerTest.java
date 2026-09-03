package com.azhukov.agent.api;

import com.azhukov.agent.core.skill.SkillManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillsDiscoveryControllerTest {

    private SkillManager skillManager;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        skillManager = mock(SkillManager.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillsDiscoveryController(skillManager)).build();
    }

    @Test
    void skillsReturnsHermesListEnvelopeWithMetadata() throws Exception {
        when(skillManager.listSkills()).thenReturn(List.of(
            new SkillManager.SkillInfo(
                "z-github", "", "GitHub workflow skill", "github", null,
                0, 0, null, false, "USER_AUTHORED", List.of(), List.of(), false, null),
            new SkillManager.SkillInfo(
                "ascii-art", "", "ASCII art generation", "creative", null,
                0, 0, null, false, "AGENT_CREATED", List.of(), List.of(), false, null)
        ));

        mockMvc.perform(get("/v1/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data[0].name").value("ascii-art"))
            .andExpect(jsonPath("$.data[0].description").value("ASCII art generation"))
            .andExpect(jsonPath("$.data[0].category").value("creative"))
            .andExpect(jsonPath("$.data[1].name").value("z-github"));
    }

    @Test
    void profilePrefixedSkillsRouteMirrorsHermesMultiplexAlias() throws Exception {
        when(skillManager.listSkills()).thenReturn(List.of(
            new SkillManager.SkillInfo(
                "core", "", "Core skill", "core", null,
                0, 0, null, false, "AGENT_CREATED", List.of(), List.of(), false, null)
        ));

        mockMvc.perform(get("/p/work/v1/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data[0].name").value("core"));
    }

    @Test
    void skillsSortsByCategoryThenNameLikeHermes() throws Exception {
        when(skillManager.listSkills()).thenReturn(List.of(
            new SkillManager.SkillInfo(
                "a-creative", "", "Creative skill", "creative", null,
                0, 0, null, false, "AGENT_CREATED", List.of(), List.of(), false, null),
            new SkillManager.SkillInfo(
                "z-core", "", "Core skill", "core", null,
                0, 0, null, false, "AGENT_CREATED", List.of(), List.of(), false, null)
        ));

        mockMvc.perform(get("/v1/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("z-core"))
            .andExpect(jsonPath("$.data[1].name").value("a-creative"));
    }

    @Test
    void skillsOmitsDisabledSkills() throws Exception {
        when(skillManager.listSkills()).thenReturn(List.of(
            new SkillManager.SkillInfo(
                "visible", "", "Visible", "core", null,
                0, 0, null, false, "AGENT_CREATED", List.of(), List.of(), false, null),
            new SkillManager.SkillInfo(
                "disabled", "", "Disabled", "core", null,
                0, 0, null, false, "AGENT_CREATED", List.of(), List.of(), true, null)
        ));

        mockMvc.perform(get("/v1/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("visible"));
    }

    @Test
    void failureReturnsStableHermesServerError() throws Exception {
        when(skillManager.listSkills()).thenThrow(new RuntimeException("secret skill detail"));

        mockMvc.perform(get("/v1/skills"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.message").value("Failed to enumerate skills"))
            .andExpect(jsonPath("$.error.type").value("server_error"))
            .andExpect(jsonPath("$.error.param").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.error.code").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret skill detail"))));
    }
}
