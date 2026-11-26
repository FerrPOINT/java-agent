package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.DefaultFileSafety;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileWorkspaceFileToolTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sameUserCanUseBothWorkspacesAndCannotUseAnotherProfilesExactFile() throws Exception {
        var properties = new AgentProperties();
        properties.getProfile().setBaseDir(temp.resolve("profiles").toString());
        var profiles = new ProfileService(properties, new RuntimeConfigService());
        Path alphaRoot = Files.createDirectories(profiles.workspacePath("alpha"));
        Path betaRoot = Files.createDirectories(profiles.workspacePath("beta"));
        Session alpha = Session.create("same-user", "openai", "fixture").withMetadata("profile", "alpha");
        Session beta = alpha.withMetadata("profile", "beta");
        var read = new ReadFileTool(properties);
        var write = new WriteFileTool(properties);
        var search = new SearchFilesTool(properties);
        var patch = new PatchTool(properties);
        var delete = new DeleteFileTool(properties, new DefaultFileSafety(properties));
        for (ToolHandler tool : java.util.List.of(read, write, search, patch, delete)) {
            ReflectionTestUtils.setField(tool, "profiles", profiles);
        }

        assertThat(call(write, alpha, Map.of("path", "private/note.txt", "content", "alpha-private")).success())
            .isTrue();
        Path source = alphaRoot.resolve("private/note.txt");
        assertThat(call(read, alpha, Map.of("path", source.toString())).content()).contains("alpha-private");

        assertThat(call(read, beta, Map.of("path", source.toString())).success()).isFalse();
        assertThat(call(write, beta, Map.of("path", source.toString(), "content", "beta-attack", "cross_profile", true))
            .success()).isFalse();
        assertThat(call(read, beta, Map.of("path", betaRoot.relativize(source).toString())).success()).isFalse();
        assertThat(call(search, beta, Map.of("path", alphaRoot.toString(), "pattern", "alpha-private")).success()).isFalse();
        assertThat(call(patch, beta, Map.of("path", source.toString(), "old_string", "alpha-private",
            "new_string", "inaccessible", "cross_profile", true)).success()).isFalse();
        assertThat(call(patch, beta, Map.of("mode", "patch", "patch",
            "*** Begin Patch\n*** Add File: before-other.txt\n+beta\n*** Move File: "
                + source + " -> moved.txt\n*** End Patch")).success()).isFalse();
        assertThat(betaRoot.resolve("before-other.txt")).doesNotExist();
        assertThat(call(delete, beta, Map.of("path", source.toString())).success()).isFalse();
        Files.createSymbolicLink(betaRoot.resolve("linked"), source.getParent());
        assertThat(call(read, beta, Map.of("path", "linked/note.txt")).success()).isFalse();
        assertThat(call(write, beta, Map.of("path", "linked/note.txt", "content", "beta-attack")).success()).isFalse();
        assertThat(Files.readString(source)).isEqualTo("alpha-private");

        assertThat(call(write, beta, Map.of("path", "private/note.txt", "content", "beta-private")).success()).isTrue();
        assertThat(call(read, beta, Map.of("path", "private/note.txt")).content()).contains("beta-private");
        assertThat(call(search, beta, Map.of("pattern", "beta-private")).content()).contains("private/note.txt");
        assertThat(call(search, beta, Map.of("pattern", "alpha-private")).content()).doesNotContain("alpha-private");
        assertThat(call(patch, beta, Map.of("path", "private/note.txt", "old_string", "beta-private",
            "new_string", "beta-updated")).success()).isTrue();
        assertThat(call(read, beta, Map.of("path", "private/note.txt")).content()).contains("beta-updated");
        assertThat(call(delete, beta, Map.of("path", "private/note.txt")).success()).isTrue();
        assertThat(Files.readString(source)).isEqualTo("alpha-private");
    }

    private ToolResult call(ToolHandler tool, Session session, Map<String, ?> args) throws Exception {
        return tool.execute(mapper.writeValueAsString(args), null, session);
    }
}
