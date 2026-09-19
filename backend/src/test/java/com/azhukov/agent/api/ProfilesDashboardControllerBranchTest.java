package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch coverage for ProfilesDashboardController: validation rejects,
 * soul/description/model updates, unknown-profile errors.
 */
class ProfilesDashboardControllerBranchTest {

    @TempDir
    private Path tempDir;

    private AgentProperties properties;
    private ProfileService profileService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getProfile().setName("default");
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        properties.getCore().setSoulMdPath(tempDir.resolve("soul.md").toString());
        properties.getModel().setProvider("openai-compatible");
        properties.getModel().setModelName("gpt-5");
        RuntimeConfigService runtimeConfigService = new RuntimeConfigService();
        profileService = new ProfileService(properties, runtimeConfigService);
        mockMvc = MockMvcBuilders.standaloneSetup(
            new ProfilesDashboardController(
                properties,
                runtimeConfigService,
                profileService,
                mock(SessionRepository.class),
                mock(MessageRepository.class),
                command -> {
                })).build();
    }

    @Test
    void createProfileRejectsInvalidName() throws Exception {
        mockMvc.perform(post("/api/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad name\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createProfileRejectsMissingName() throws Exception {
        mockMvc.perform(post("/api/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createProfileCreatesDirectoryAndRow() throws Exception {
        mockMvc.perform(post("/api/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"work\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("work"));
    }

    @Test
    void activeProfileListed() throws Exception {
        mockMvc.perform(get("/api/profiles/active"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value("default"));
    }

    @Test
    void setActiveProfileUnknownReturns404() throws Exception {
        mockMvc.perform(post("/api/profiles/active")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ghost\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void renameProfileUnknownReturns404() throws Exception {
        mockMvc.perform(patch("/api/profiles/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-name\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteUnknownProfileReturns404() throws Exception {
        mockMvc.perform(delete("/api/profiles/ghost"))
            .andExpect(status().isNotFound());
    }

    @Test
    void soulUnknownProfileReturns404() throws Exception {
        mockMvc.perform(get("/api/profiles/ghost/soul"))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateSoulRejectsMissingContent() throws Exception {
        mockMvc.perform(put("/api/profiles/default/soul")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("content is required"));
    }

    @Test
    void updateSoulUnknownProfileReturns404() throws Exception {
        mockMvc.perform(put("/api/profiles/ghost/soul")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"be helpful\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void setupCommandUnknownProfileReturns404() throws Exception {
        mockMvc.perform(get("/api/profiles/ghost/setup-command"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));
    }

    @Test
    void setupCommandInvalidNameReturns400() throws Exception {
        mockMvc.perform(get("/api/profiles/BAD NAME/setup-command"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void describeAutoUnknownProfileReturns404() throws Exception {
        mockMvc.perform(post("/api/profiles/ghost/describe-auto"))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateModelRejectsMissingProvider() throws Exception {
        mockMvc.perform(put("/api/profiles/default/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"gpt-4o\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("provider and model are required"));
    }

    @Test
    void updateModelUnknownProfileReturns404() throws Exception {
        mockMvc.perform(put("/api/profiles/ghost/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"openai-compatible\",\"model\":\"gpt-4o\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateDescriptionUnknownProfileReturns404() throws Exception {
        mockMvc.perform(put("/api/profiles/ghost/description")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"x\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void openTerminalUnknownProfileReturns404() throws Exception {
        mockMvc.perform(post("/api/profiles/ghost/open-terminal"))
            .andExpect(status().is4xxClientError());
    }
}
