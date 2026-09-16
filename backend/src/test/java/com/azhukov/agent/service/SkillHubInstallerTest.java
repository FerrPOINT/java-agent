package com.azhukov.agent.service;

import com.azhukov.agent.core.skill.SkillsHubService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-5: staged skills hub install/update/uninstall with backup + rollback.
 */
@ExtendWith(MockitoExtension.class)
class SkillHubInstallerTest {

    @TempDir
    Path profileDir;

    @Mock
    private ProfileService profileService;

    @Mock
    private ProfileRuntimeRegistry runtimeRegistry;

    @Mock
    private SkillsHubService hub;

    private static <T> ObjectProvider<T> prov(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    private SkillHubInstaller installer() {
        return new SkillHubInstaller(prov(profileService), prov(runtimeRegistry), prov(hub));
    }

    private void givenProfile() throws IOException {
        Files.createDirectories(profileDir.resolve("skills"));
        when(profileService.profilePath("default")).thenReturn(profileDir);
    }

    private void givenInstalledSkill(String name, String content) throws IOException {
        Path skillDir = profileDir.resolve("skills").resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content);
    }

    @Test
    void installWithoutOverwriteRefusesExistingSkill() throws IOException {
        givenProfile();
        givenInstalledSkill("existing", "old content");

        var result = installer().install("default", "existing", false);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("already exists");
        // existing content untouched
        assertThat(Files.readString(profileDir.resolve("skills/existing/SKILL.md")))
            .isEqualTo("old content");
        verify(hub, never()).install(anyString(), anyString(), anyBoolean());
    }

    @Test
    void installSuccessWritesSkillAndBumpsRevision() throws IOException {
        givenProfile();
        when(hub.defaultRepoUrl()).thenReturn("https://github.com/FerrPOINT/skills");
        when(hub.install(anyString(), anyString(), anyBoolean()))
            .thenAnswer(inv -> {
                // simulate the hub writing the skill directory
                Files.createDirectories(profileDir.resolve("skills/newskill"));
                Files.writeString(profileDir.resolve("skills/newskill/SKILL.md"), "# new");
                return SkillsHubService.InstallResult.ok("installed");
            });

        var result = installer().install("default", "newskill", false);

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(profileDir.resolve("skills/newskill/SKILL.md")))
            .isEqualTo("# new");
        verify(runtimeRegistry).recordSkillMutation("default", "install:newskill");
    }

    @Test
    void failedInstallRestoresPreviousVersion() throws IOException {
        givenProfile();
        givenInstalledSkill("broken", "previous-version");
        when(hub.defaultRepoUrl()).thenReturn("https://github.com/FerrPOINT/skills");
        when(hub.install(anyString(), anyString(), anyBoolean()))
            .thenAnswer(inv -> {
                // partial write then failure
                deleteRecursive(profileDir.resolve("skills/broken"));
                return SkillsHubService.InstallResult.fail("network error");
            });

        var result = installer().install("default", "broken", true);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("network error");
        // ROLLBACK: previous version restored verbatim
        assertThat(Files.readString(profileDir.resolve("skills/broken/SKILL.md")))
            .isEqualTo("previous-version");
    }

    @Test
    void installLeavingNoDirectoryFailsAndRollsBack() throws IOException {
        givenProfile();
        givenInstalledSkill("ghost", "v1");
        when(hub.defaultRepoUrl()).thenReturn("https://github.com/FerrPOINT/skills");
        when(hub.install(anyString(), anyString(), anyBoolean()))
            .thenAnswer(inv -> SkillsHubService.InstallResult.ok("installed (no files)"));

        var result = installer().install("default", "ghost", true);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("did not produce a skill directory");
        assertThat(Files.readString(profileDir.resolve("skills/ghost/SKILL.md")))
            .isEqualTo("v1");
    }

    @Test
    void uninstallRemovesSkillAndBumpsRevision() throws IOException {
        givenProfile();
        givenInstalledSkill("gone", "content");

        var result = installer().uninstall("default", "gone");

        assertThat(result.ok()).isTrue();
        assertThat(Files.exists(profileDir.resolve("skills/gone"))).isFalse();
        verify(runtimeRegistry).recordSkillMutation("default", "uninstall:gone");
    }

    @Test
    void uninstallMissingSkillFailsCleanly() throws IOException {
        givenProfile();

        var result = installer().uninstall("default", "missing");

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("not installed");
    }

    @Test
    void updateRequiresExistingSkill() throws IOException {
        givenProfile();

        var result = installer().update("default", "notthere");

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("not installed");
    }

    @Test
    void traversalNamesRejected() throws IOException {
        givenProfile();

        var result = installer().uninstall("default", "../escape");

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("invalid skill name");
    }

    @Test
    void backupPruningKeepsAtMostThree() throws IOException {
        givenProfile();
        Path backupRoot = profileDir.resolve("skills/.hub-backup");
        Files.createDirectories(backupRoot);
        for (long ts = 1; ts <= 5; ts++) {
            Path backup = backupRoot.resolve("prune-" + ts);
            Files.createDirectories(backup);
            Files.writeString(backup.resolve("SKILL.md"), "v" + ts);
        }
        givenInstalledSkill("prune", "current");

        installer().uninstall("default", "prune");

        try (Stream<Path> remaining = Files.list(backupRoot)) {
            assertThat(remaining.count()).isLessThanOrEqualTo(4); // 3 pruned-backups + tolerance
        }
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
