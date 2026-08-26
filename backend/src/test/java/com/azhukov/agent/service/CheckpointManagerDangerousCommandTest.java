package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.repository.CheckpointFileRepository;
import com.azhukov.agent.persistence.repository.CheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L36 test: verify that isDangerousCommand uses word-boundary regex for chown,
 * avoiding false positives like "echo mychown" or filenames containing "chown".
 */
@ExtendWith(MockitoExtension.class)
class CheckpointManagerDangerousCommandTest {

    @Mock private CheckpointRepository checkpointRepository;
    @Mock private CheckpointFileRepository checkpointFileRepository;
    private AgentProperties properties;
    private CheckpointManager manager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        properties.getCheckpoints().setEnabled(true);
        manager = new CheckpointManager(checkpointRepository, checkpointFileRepository, properties, new ObjectMapper(),
            new org.springframework.beans.factory.ObjectProvider<>() {
                @Override public CheckpointManager getObject() { return manager; }
            });
    }

    @Test
    void realChownCommandIsDangerous() {
        assertThat(manager.isDangerousCommand("chown root:root /file")).isTrue();
        assertThat(manager.isDangerousCommand("chown root /file")).isTrue();
        assertThat(manager.isDangerousCommand("sudo chown user /home/data")).isTrue();
    }

    @Test
    void echoChownIsNotDangerous() {
        // "echo mychown" should NOT match because "mychown" is not the word "chown"
        assertThat(manager.isDangerousCommand("echo mychown")).isFalse();
        assertThat(manager.isDangerousCommand("cat /tmp/notchowntest")).isFalse();
        assertThat(manager.isDangerousCommand("ls /home/chownbackup")).isFalse();
    }

    @Test
    void chownAsSubstringInPathIsNotDangerous() {
        assertThat(manager.isDangerousCommand("cd /opt/chownalyzer/")).isFalse();
        assertThat(manager.isDangerousCommand("python chown_helper.py")).isFalse();
    }
}