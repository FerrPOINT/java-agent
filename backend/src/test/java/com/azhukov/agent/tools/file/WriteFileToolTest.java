package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WriteFileToolTest {

    @Test
    void writesFile(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p, fileSafety());
        Path file = dir.resolve("sub/a.txt");
        ToolResult r = t.execute("{\"path\":\"" + file + "\",\"content\":\"hello\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("hello");
    }

    @Test
    void requiresPath() {
        WriteFileTool t = new WriteFileTool(new AgentProperties(), fileSafety());
        ToolResult r = t.execute("{\"content\":\"hello\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void blocksForbiddenPath() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p, fileSafety());
        ToolResult r = t.execute("{\"path\":\"/root/.ssh/key\",\"content\":\"x\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void respectsAllowedPaths(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(true);
        p.getSecurity().setAllowedPaths(java.util.List.of(dir.toString()));
        WriteFileTool t = new WriteFileTool(p, fileSafety());
        Path file = dir.resolve("a.txt");
        ToolResult r = t.execute("{\"path\":\"" + file + "\",\"content\":\"ok\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
    }

    @Test
    void deniesPathOutsideAllowed(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(true);
        p.getSecurity().setAllowedPaths(java.util.List.of(dir.toString()));
        WriteFileTool t = new WriteFileTool(p, fileSafety());
        ToolResult r = t.execute("{\"path\":\"/tmp/outside.txt\",\"content\":\"x\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    private static com.azhukov.agent.core.security.DefaultFileSafety fileSafety() {
        com.azhukov.agent.config.AgentProperties props = new com.azhukov.agent.config.AgentProperties();
        props.getSecurity().setFileSafetyEnabled(true);
        return new com.azhukov.agent.core.security.DefaultFileSafety(props);
    }
}
