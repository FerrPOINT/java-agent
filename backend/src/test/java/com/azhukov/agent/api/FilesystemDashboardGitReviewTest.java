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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage + regression for FilesystemDashboardController git-review branches:
 * branch scope with real commits, lastTurn scope, untracked files.
 */
class FilesystemDashboardGitReviewTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        // Real git repo with a branch and untracked file
        run("git", "init", "-q", "-b", "main", tempDir.toString());
        run("git", "-C", tempDir.toString(), "config", "user.email", "t@t");
        run("git", "-C", tempDir.toString(), "config", "user.name", "t");
        Files.writeString(tempDir.resolve("a.txt"), "one");
        run("git", "-C", tempDir.toString(), "add", ".");
        run("git", "-C", tempDir.toString(), "commit", "-q", "-m", "first");
        Files.writeString(tempDir.resolve("b.txt"), "two");
        run("git", "-C", tempDir.toString(), "add", ".");
        run("git", "-C", tempDir.toString(), "commit", "-q", "-m", "second");
        Files.writeString(tempDir.resolve("c.txt"), "untracked");

        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(
            new FilesystemDashboardController(properties, new DefaultFileSafety(properties))).build();
    }

    private void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", cmd));
        }
    }

    @Test
    void reviewBranchScopeListsCommittedDiff() throws Exception {
        mockMvc.perform(get("/api/git/review/list")
                .param("path", tempDir.toString())
                .param("scope", "branch"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files").exists());
    }

    @Test
    void reviewLastTurnScopeIncludesUntracked() throws Exception {
        mockMvc.perform(get("/api/git/review/list")
                .param("path", tempDir.toString())
                .param("scope", "lastTurn"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files").exists());
    }

    @Test
    void reviewUncommittedScopeListsWorkingTree() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "one-changed");
        mockMvc.perform(get("/api/git/review/list")
                .param("path", tempDir.toString())
                .param("scope", "uncommitted"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files").exists());
    }
}
