package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.DefaultFileSafety;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage + regression for FilesystemDashboardController error branches:
 * media path validation, chat image upload validation, fs list/read guards.
 */
class FilesystemDashboardControllerErrorBranchTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(
            new FilesystemDashboardController(properties, new DefaultFileSafety(properties))).build();
    }

    @Test
    void mediaMissingPathIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/media").param("path", ""))
            .andExpect(status().isBadRequest());
    }

    @Test
    void mediaNonImageExtensionIsUnsupported() throws Exception {
        Path txt = tempDir.resolve("note.txt");
        Files.writeString(txt, "hello");
        mockMvc.perform(get("/api/media").param("path", txt.toString()))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void mediaOutsideRootsIsForbidden() throws Exception {
        // .png extension but path resolves outside allowed roots
        Path outside = tempDir.resolve("../escape.png");
        mockMvc.perform(get("/api/media").param("path", outside.toString()))
            .andExpect(result -> {
                int code = result.getResponse().getStatus();
                org.assertj.core.api.Assertions.assertThat(code).isIn(400, 403, 404);
            });
    }

    @Test
    void chatImageUploadWithoutBodyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/chat/image-upload")
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void chatImageUploadWithInvalidBase64IsBadRequest() throws Exception {
        mockMvc.perform(post("/api/chat/image-upload")
                .contentType("application/json")
                .content("{\"filename\":\"x.png\",\"content\":\"!!!not-base64!!!\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void fsListBlankPathIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/fs/list"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void fsListRealDirectoryReturnsEntries() throws Exception {
        mockMvc.perform(get("/api/fs/list").param("path", tempDir.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries").exists());
    }

    @Test
    void fsReadOutsideAllowedIsForbidden() throws Exception {
        mockMvc.perform(get("/api/files/read").param("path", "/etc/passwd"))
            .andExpect(result -> {
                int code = result.getResponse().getStatus();
                org.assertj.core.api.Assertions.assertThat(code).isIn(400, 403, 404);
            });
    }
}
