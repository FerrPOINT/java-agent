package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadWriteFileToolsTest {

    private final Session session = Session.create("u","p","m");

    private AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        return p;
    }

    @Test
    void readFileReturnsLines(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("x.txt");
        Files.writeString(f, "a\nb\nc\n");
        ReadFileTool t = new ReadFileTool(props());
        ToolResult r = t.execute("{\"path\":\"" + f + "\",\"offset\":1,\"limit\":2}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).isEqualTo("1|a\n2|b\n");
    }

    @Test
    void readFileFailsWhenNotAllowed(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(true);
        p.getSecurity().setAllowedPaths(List.of("/tmp/other"));
        ReadFileTool t = new ReadFileTool(p);
        ToolResult r = t.execute("{\"path\":\"" + dir.resolve("x.txt") + "\"}", null, session);
        assertThat(r.success()).isFalse();
    }

    @Test
    void writeFileCreatesContent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sub/y.txt");
        WriteFileTool t = new WriteFileTool(props());
        ToolResult r = t.execute("{\"path\":\"" + f + "\",\"content\":\"hello\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(f)).isEqualTo("hello");
    }

    @Test
    void writeFileBlocksSensitivePath() {
        WriteFileTool t = new WriteFileTool(props());
        ToolResult r = t.execute("{\"path\":\"/.env\",\"content\":\"x\"}", null, session);
        assertThat(r.success()).isFalse();
    }
}
