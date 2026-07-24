package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ReferenceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DefaultContextReferenceServiceTest {

    @Test
    void resolvesFileUrlAndSkill(@TempDir Path dir) throws Exception {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(dir.toString());
        var svc = new DefaultContextReferenceService(props, mock(com.azhukov.agent.core.skill.SkillManager.class));

        Path file = dir.resolve("ref.txt");
        Files.writeString(file, "hello ref");

        var refs = svc.resolve(List.of("file://" + file.toString(), "https://example.com", "skill://test", "unknown://x"));
        assertThat(refs).hasSize(4);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.FILE);
        assertThat(refs.get(1).type()).isEqualTo(ReferenceType.URL);
        assertThat(refs.get(2).type()).isEqualTo(ReferenceType.SKILL);
        assertThat(refs.get(3).type()).isEqualTo(ReferenceType.UNKNOWN);
    }

    @Test
    void loadsExistingFileWithinWorkingDir(@TempDir Path dir) throws Exception {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(dir.toString());
        var svc = new DefaultContextReferenceService(props, mock(com.azhukov.agent.core.skill.SkillManager.class));
        Path file = dir.resolve("data.txt");
        Files.writeString(file, "data content");

        var content = svc.loadContent(new com.azhukov.agent.core.model.ContextReference(ReferenceType.FILE, file.toString(), "data.txt", null));
        assertThat(content).isPresent();
        assertThat(content.get()).contains("data content");
    }

    @Test
    void deniesFileOutsideWorkingDir(@TempDir Path dir) {
        AgentProperties props = new AgentProperties();
        props.getCore().setWorkingDirectory(dir.resolve("sub").toString());
        var svc = new DefaultContextReferenceService(props, mock(com.azhukov.agent.core.skill.SkillManager.class));

        var content = svc.loadContent(new com.azhukov.agent.core.model.ContextReference(ReferenceType.FILE, "/etc/passwd", "passwd", null));
        assertThat(content).isPresent();
        assertThat(content.get()).contains("access denied");
    }
}
