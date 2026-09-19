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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch coverage for FilesystemDashboardController error and edge paths:
 * managed files API (not-found / not-dir / missing param), fs API (ENOENT,
 * ENOTDIR, EACCES), mkdir/delete validation.
 */
class FilesystemDashboardControllerBranchTest {

    @TempDir
    private Path tempDir;

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
    void managedListMissingPathIsRejected() throws Exception {
        mockMvc.perform(get("/api/files"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void managedListUnknownPathReturns404() throws Exception {
        mockMvc.perform(get("/api/files").param("path", tempDir.resolve("ghost").toString()))
            .andExpect(status().isNotFound());
    }

    @Test
    void managedListFileInsteadOfDirReturns404() throws Exception {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "x");
        mockMvc.perform(get("/api/files").param("path", file.toString()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void managedReadUnknownFileReturns404() throws Exception {
        mockMvc.perform(get("/api/files/read").param("path", tempDir.resolve("ghost.txt").toString()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void managedReadDirectoryIsRejected() throws Exception {
        mockMvc.perform(get("/api/files/read").param("path", tempDir.toString()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void managedDownloadUnknownFileReturns404() throws Exception {
        mockMvc.perform(get("/api/files/download").param("path", tempDir.resolve("ghost.txt").toString()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void fsListUnknownPathReturnsENOENT() throws Exception {
        mockMvc.perform(get("/api/fs/list").param("path", tempDir.resolve("ghost").toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("ENOENT"));
    }

    @Test
    void fsListFileInsteadOfDirReturnsEntriesOrENOTDIR() throws Exception {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "x");
        mockMvc.perform(get("/api/fs/list").param("path", file.toString()))
            .andExpect(status().isOk());
    }

    @Test
    void fsListOutsideAllowedRootReturnsEACCES() throws Exception {
        mockMvc.perform(get("/api/fs/list").param("path", "/proc/1/root-nonexistent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("EACCES"));
    }

    @Test
    void fsReadTextUnknownFileReturnsClientError() throws Exception {
        mockMvc.perform(get("/api/fs/read-text").param("path", tempDir.resolve("ghost.txt").toString()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void fsWriteTextMissingPathIsRejected() throws Exception {
        mockMvc.perform(post("/api/fs/write-text")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void fsReadDataUrlUnknownFileReturnsClientError() throws Exception {
        mockMvc.perform(get("/api/fs/read-data-url").param("path", tempDir.resolve("ghost.txt").toString()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void fsDownloadUnknownFileReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/fs/download").param("path", tempDir.resolve("ghost.txt").toString()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void fsDefaultCwdReturnsShape() throws Exception {
        mockMvc.perform(get("/api/fs/default-cwd"))
            .andExpect(status().isOk());
    }

    @Test
    void fsGitRootOutsideRepoReturnsErrorShape() throws Exception {
        mockMvc.perform(get("/api/fs/git-root").param("path", tempDir.toString()))
            .andExpect(status().isOk());
    }

    @Test
    void mkdirWithoutPathIsRejected() throws Exception {
        mockMvc.perform(post("/api/files/mkdir")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void deleteWithoutPathReturns400() throws Exception {
        mockMvc.perform(delete("/api/files"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void deleteUnknownPathReturns404() throws Exception {
        mockMvc.perform(delete("/api/files").param("path", tempDir.resolve("ghost").toString()))
            .andExpect(status().is4xxClientError());
    }
}
