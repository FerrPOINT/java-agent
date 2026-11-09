package com.azhukov.agent.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs(OS.LINUX)
class SecureFileAccessTest {
    @Test
    void refusesToPublishAReplacedStagingEntry(@TempDir Path root) throws Exception {
        Path target = Files.writeString(root.resolve("target"), "original");
        Path other = Files.writeString(root.resolve("other"), "unrelated");
        try (var directory = SecureFileAccess.openDirectory(root)) {
            assertThatThrownBy(() -> directory.replaceFile(target.getFileName(),
                "updated".getBytes(StandardCharsets.UTF_8), () -> {
                    Path stage;
                    try (var paths = Files.list(root)) {
                        stage = paths.filter(path -> path.getFileName().toString().startsWith(".java-agent-stage-"))
                            .findFirst().orElseThrow();
                    }
                    assertThat(Files.getPosixFilePermissions(stage))
                        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
                    Path payload = stage.resolve(".payload");
                    Files.delete(payload);
                    Files.createSymbolicLink(payload, other);
                }))
                .isInstanceOf(IOException.class).hasMessageContaining("Staged file changed");
        }
        assertThat(Files.readString(target)).isEqualTo("original");
        assertThat(Files.readString(other)).isEqualTo("unrelated");
        try (var paths = Files.list(root)) {
            assertThat(paths).containsExactlyInAnyOrder(target, other);
        }
    }
}
