package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileSafetyValidatorTest {

    private AgentProperties defaultProps() {
        return new AgentProperties();
    }

    @Test
    void readAllowsNormalFile() {
        FileSafetyValidator v = new FileSafetyValidator(defaultProps());
        assertThat(v.checkRead(Paths.get("/tmp/readme.txt"))).isNull();
    }

    @Test
    void readBlocksSensitiveFile() {
        FileSafetyValidator v = new FileSafetyValidator(defaultProps());
        assertThat(v.checkRead(Paths.get("/tmp/.env"))).contains("sensitive file");
    }

    @Test
    void writeBlocksKeyExtension() {
        FileSafetyValidator v = new FileSafetyValidator(defaultProps());
        assertThat(v.checkWrite(Paths.get("/tmp/key.pem"))).contains("extension");
    }

    @Test
    void writeAllowsTxt() {
        FileSafetyValidator v = new FileSafetyValidator(defaultProps());
        assertThat(v.checkWrite(Paths.get("/tmp/file.txt"))).isNull();
    }

    @Test
    void nullPathReturnsError() {
        FileSafetyValidator v = new FileSafetyValidator(defaultProps());
        assertThat(v.checkRead(null)).isEqualTo("Path is null");
        assertThat(v.checkWrite(null)).isEqualTo("Path is null");
    }

    @Test
    void allowedPathsRestrictsAccess() {
        AgentProperties p = defaultProps();
        p.getSecurity().setAllowedPaths(List.of("/allowed"));
        FileSafetyValidator v = new FileSafetyValidator(p);
        assertThat(v.checkRead(Paths.get("/allowed/file.txt"))).isNull();
        assertThat(v.checkRead(Paths.get("/other/file.txt"))).contains("outside allowed roots");
    }
}
