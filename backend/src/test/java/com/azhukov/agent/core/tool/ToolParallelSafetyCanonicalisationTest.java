package com.azhukov.agent.core.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * h69: Tests for parallel-batch path canonicalisation.
 * Uses Path.toRealPath() or Path.normalize() to detect same-file concurrent mutation.
 */
class ToolParallelSafetyCanonicalisationTest {

    @TempDir
    Path tempDir;

    private Path createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException | SecurityException e) {
            assumeTrue(false, "Symbolic links are not available in this test environment: " + e.getMessage());
            return link;
        }
    }

    @Test
    void canonicalise_existingFile_resolvesToRealPath() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "test");
        Path canonical = ToolParallelSafety.canonicalise(file);
        // toRealPath() should resolve to the same absolute path
        assertThat(canonical).isEqualTo(file.toRealPath());
    }

    @Test
    void canonicalise_nonExistentFile_fallsBackToNormalize() {
        Path nonExistent = tempDir.resolve("nonexistent").resolve("file.txt");
        Path canonical = ToolParallelSafety.canonicalise(nonExistent);
        // Should fall back to normalize() since the file doesn't exist
        assertThat(canonical).isEqualTo(nonExistent.normalize());
    }

    @Test
    void canonicalise_resolvesDotAndDotDot() {
        Path withDots = tempDir.resolve(".").resolve("subdir").resolve("..").resolve("file.txt");
        Path canonical = ToolParallelSafety.canonicalise(withDots);
        // . and .. should be resolved
        assertThat(canonical.toString()).doesNotContain("/./");
        assertThat(canonical.toString()).doesNotContain("/../");
    }

    @Test
    void canonicalise_resolvesSymlinks() throws IOException {
        Path realFile = tempDir.resolve("real.txt");
        Files.writeString(realFile, "content");
        Path symlink = tempDir.resolve("link.txt");
        createSymlinkOrSkip(symlink, realFile);

        Path canonicalReal = ToolParallelSafety.canonicalise(realFile);
        Path canonicalSymlink = ToolParallelSafety.canonicalise(symlink);

        // Both should resolve to the same real path
        assertThat(canonicalSymlink).isEqualTo(canonicalReal);
    }

    @Test
    void overlappingPaths_symlinkDetectedAsOverlap() throws IOException {
        // Create a real file and a symlink to it
        Path realFile = tempDir.resolve("config.json");
        Files.writeString(realFile, "{}");
        Path symlink = tempDir.resolve("link-to-config.json");
        createSymlinkOrSkip(symlink, realFile);

        // Canonicalise both — they should resolve to the same path
        Path canonicalReal = ToolParallelSafety.canonicalise(realFile);
        Path canonicalSymlink = ToolParallelSafety.canonicalise(symlink);

        assertThat(canonicalSymlink).isEqualTo(canonicalReal);
    }

    @Test
    void overlappingPaths_dotNotationResolved() {
        Path path1 = tempDir.resolve("dir").resolve("file.txt");
        Path path2 = tempDir.resolve("dir").resolve(".").resolve("file.txt");

        Path canon1 = ToolParallelSafety.canonicalise(path1);
        Path canon2 = ToolParallelSafety.canonicalise(path2);

        // Both should resolve to the same canonical path
        assertThat(canon1).isEqualTo(canon2);
    }

    @Test
    void overlappingPaths_relativeVsAbsoluteResolved() throws IOException {
        // Test that relative and absolute paths to the same file are detected as overlapping
        // after canonicalisation
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "test");

        Path absPath = file.toAbsolutePath();
        Path viaDot = tempDir.resolve(".").resolve("test.txt").toAbsolutePath();

        Path canon1 = ToolParallelSafety.canonicalise(absPath);
        Path canon2 = ToolParallelSafety.canonicalise(viaDot);

        assertThat(canon1).isEqualTo(canon2);
    }
}
