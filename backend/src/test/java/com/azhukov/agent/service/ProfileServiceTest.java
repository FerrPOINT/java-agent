package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProfileServiceTest {

    @TempDir
    private Path tempDir;

    private AgentProperties properties;
    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getProfile().setName("default");
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        properties.getCore().setSoulMdPath(tempDir.resolve("soul.md").toString());
        properties.getModel().setProvider("openai-compatible");
        properties.getModel().setModelName("gpt-5");
        profileService = new ProfileService(properties, new RuntimeConfigService());
    }

    @Test
    void validatesProfileIdsLikeHermes() {
        assertThat(profileService.normalizeProfileName("  Work_Bot ")).isEqualTo("work_bot");
        assertThat(profileService.isValidNamedProfileName("work-bot")).isTrue();

        assertThatThrownBy(() -> profileService.validateProfileName("../work"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profileService.validateProfileName("hermes"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profileService.createProfile(new ProfileService.CreateProfileRequest(
            "default", null, false, false, false, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createProfileBootstrapsIndependentHomeAndMetadata() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "Coder",
            null,
            false,
            false,
            true,
            "backend owner",
            "openrouter",
            "anthropic/claude-sonnet-4-5",
            "https://openrouter.example/api/v1"));

        Path coder = tempDir.resolve("profiles").resolve("coder");
        assertThat(coder).isDirectory();
        assertThat(coder.resolve("memories")).isDirectory();
        assertThat(coder.resolve("sessions")).isDirectory();
        assertThat(coder.resolve("skills")).isDirectory();
        assertThat(coder.resolve("cron")).isDirectory();
        assertThat(coder.resolve(".env")).isRegularFile();
        assertThat(coder.resolve(".no-bundled-skills")).isRegularFile();
        assertThat(coder.resolve("SOUL.md")).isRegularFile();
        assertThat(Files.readString(coder.resolve("profile.yaml")))
            .contains("description: backend owner")
            .contains("description_auto: false");
        assertThat(Files.readString(coder.resolve("config.yaml")))
            .contains("provider: openrouter")
            .contains("default: anthropic/claude-sonnet-4-5")
            .contains("base_url: https://openrouter.example/api/v1");

        assertThat(profileService.listProfileRows())
            .anySatisfy(row -> {
                assertThat(row).containsEntry("name", "coder");
                assertThat(row).containsEntry("description", "backend owner");
                assertThat(row).containsEntry("provider", "openrouter");
                assertThat(row).containsEntry("model", "anthropic/claude-sonnet-4-5");
            });
    }

    @Test
    void readAndWriteConfigAreScopedToKnownProfileHome() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));

        profileService.writeConfig("work", Map.of(
            "platform_toolsets", Map.of("cli", List.of("web", "terminal")),
            "web", Map.of("backend", "searxng")));

        assertThat(profileService.readConfig("work"))
            .containsKey("platform_toolsets")
            .containsKey("web");
        assertThat(Files.readString(tempDir.resolve("profiles").resolve("work").resolve("config.yaml")))
            .contains("platform_toolsets:")
            .contains("backend: searxng");
        assertThat(tempDir.resolve("config.yaml")).doesNotExist();

        assertThatThrownBy(() -> profileService.readConfig("ghost"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Unknown profile: ghost");
        assertThatThrownBy(() -> profileService.writeConfig("../bad", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeModelCanPersistExplicitApiKeyWithoutLeakingItInResponse() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));

        Map<String, Object> response = profileService.writeModel(
            "work",
            "openrouter",
            "test/model-1",
            "https://openrouter.example/api/v1",
            "profile-secret");

        assertThat(response)
            .containsEntry("provider", "openrouter")
            .containsEntry("model", "test/model-1")
            .containsEntry("base_url", "https://openrouter.example/api/v1")
            .doesNotContainKey("api_key");
        assertThat(Files.readString(tempDir.resolve("profiles").resolve("work").resolve("config.yaml")))
            .contains("api_key: profile-secret");
    }

    @Test
    void rawConfigReadAndWriteAreScopedAndRequireYamlMapping() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));

        profileService.writeRawConfig("work", "model:\n  provider: openrouter\n  default: gpt-5\n");

        assertThat(profileService.configPath("work"))
            .isEqualTo(tempDir.resolve("profiles").resolve("work").resolve("config.yaml").toAbsolutePath().normalize());
        assertThat(profileService.readRawConfig("work"))
            .contains("provider: openrouter")
            .contains("default: gpt-5");

        assertThatThrownBy(() -> profileService.writeRawConfig("work", "[]"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("YAML must be a mapping");
        assertThatThrownBy(() -> profileService.readRawConfig("ghost"))
            .isInstanceOf(java.io.FileNotFoundException.class)
            .hasMessage("Unknown profile: ghost");
    }

    @Test
    void cloneConfigCopiesOnlyProfileIdentitySurface() throws Exception {
        Path defaultHome = tempDir;
        Files.writeString(defaultHome.resolve("config.yaml"), "model:\n  provider: openai\n  default: gpt-5\n");
        Files.writeString(defaultHome.resolve(".env"), "OPENAI_API_KEY=secret\n");
        Files.writeString(defaultHome.resolve("SOUL.md"), "Root soul.\n");
        Files.createDirectories(defaultHome.resolve("skills").resolve("demo"));
        Files.writeString(defaultHome.resolve("skills").resolve("demo").resolve("SKILL.md"), "---\nname: demo\n---\n");
        Files.createDirectories(defaultHome.resolve("memories"));
        Files.writeString(defaultHome.resolve("memories").resolve("MEMORY.md"), "Remember.\n");

        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "clone",
            "default",
            true,
            false,
            false,
            null,
            null,
            null,
            null));

        Path clone = tempDir.resolve("profiles").resolve("clone");
        assertThat(Files.readString(clone.resolve("config.yaml"))).contains("default: gpt-5");
        assertThat(Files.readString(clone.resolve(".env"))).contains("OPENAI_API_KEY=secret");
        assertThat(Files.readString(clone.resolve("SOUL.md"))).isEqualTo("Root soul.\n");
        assertThat(clone.resolve("skills").resolve("demo").resolve("SKILL.md")).isRegularFile();
        assertThat(clone.resolve("memories").resolve("MEMORY.md")).isRegularFile();
    }

    @Test
    void activeRenameDeleteAndSoulWritesAreDurable() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, false, null, null, null, null));
        profileService.setActiveProfile("work");

        assertThat(profileService.activeProfileName()).isEqualTo("work");
        assertThat(Files.readString(tempDir.resolve("active_profile"))).isEqualTo("work\n");

        profileService.renameProfile("work", "renamed");
        assertThat(profileService.activeProfileName()).isEqualTo("renamed");
        assertThat(tempDir.resolve("profiles").resolve("work")).doesNotExist();
        assertThat(tempDir.resolve("profiles").resolve("renamed")).isDirectory();

        profileService.writeSoul("renamed", "# Persona\n");
        assertThat(Files.readString(tempDir.resolve("profiles").resolve("renamed").resolve("SOUL.md")))
            .isEqualTo("# Persona\n");

        profileService.setActiveProfile("default");
        assertThat(profileService.activeProfileName()).isEqualTo("default");
        assertThat(tempDir.resolve("active_profile")).doesNotExist();

        profileService.deleteProfile("renamed");
        assertThat(tempDir.resolve("profiles").resolve("renamed")).doesNotExist();
    }

    @Test
    void defaultSoulUsesConfiguredPathWithoutCreatingNamedProfile() throws Exception {
        profileService.writeSoul("default", "Root persona");

        assertThat(Files.readString(tempDir.resolve("soul.md"))).isEqualTo("Root persona");
        assertThat(profileService.readSoul("default")).containsEntry("exists", true);
        assertThat(tempDir.resolve("profiles").resolve("default")).doesNotExist();
    }

    @Test
    void profileYamlSymlinkSurvivesMetadataWrites() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, false, null, null, null, null));
        Path profileDir = tempDir.resolve("profiles").resolve("work");
        Path link = profileDir.resolve("profile.yaml");
        Path dotfiles = tempDir.resolve("dotfiles");
        Files.createDirectories(dotfiles);
        Path real = dotfiles.resolve("profile.yaml");
        Files.writeString(real, "description: from dotfiles\n", StandardCharsets.UTF_8);
        Files.deleteIfExists(link);
        assumeTrue(tryCreateSymbolicLink(link, real), "symbolic links are unavailable on this filesystem");

        profileService.writeDescription("work", "updated from dashboard");

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readString(real, StandardCharsets.UTF_8))
            .contains("description: updated from dashboard")
            .contains("description_auto: false");
        try (var files = Files.list(profileDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void profileYamlWritesUnicodeWithoutEscapingAstralCharacters() throws Exception {
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "wizard", null, false, false, false, null, null, null, null));
        String wizardEmoji = new String(Character.toChars(0x1F9D9));
        String description = "Code wizard " + wizardEmoji + " sparkle";

        profileService.writeDescription("wizard", description);

        Path profileYaml = tempDir.resolve("profiles").resolve("wizard").resolve("profile.yaml");
        String raw = Files.readString(profileYaml, StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("\\U", "\\u");
        assertThat(profileService.listProfileRows())
            .anySatisfy(row -> assertThat(row).containsEntry("description", description));
    }

    @Test
    void exportProfileCreatesCredentialFreeScrubbedTarGz() throws Exception {
        String leaked = "sk-or-v1-reallyLongSecretKeyValue12345678";
        Path profile = tempDir.resolve("profiles").resolve("scrubme");
        Files.createDirectories(profile.resolve("memories"));
        Files.createDirectories(profile.resolve("skills").resolve("demo"));
        Files.writeString(profile.resolve("config.yaml"), "model: gpt-5\n");
        Files.writeString(profile.resolve(".env"), "OPENAI_API_KEY=" + leaked + "\n");
        Files.writeString(profile.resolve("auth.json"), "{\"token\":\"" + leaked + "\"}");
        Files.writeString(profile.resolve("SOUL.md"), "key " + leaked + "\n");
        Files.writeString(profile.resolve("memories").resolve("MEMORY.md"), "token " + leaked + "\n");
        Files.writeString(profile.resolve("skills").resolve("demo").resolve("SKILL.md"),
            "---\nname: demo\n---\nOPENROUTER_API_KEY=" + leaked + "\n");

        Map<String, Object> result = profileService.exportProfile(
            "scrubme",
            tempDir.resolve("exports").resolve("scrubme.tgz").toString(),
            Map.of("desktop.json", "{\"theme\":\"dark\"}"));

        Path archive = Path.of((String) result.get("archive"));
        assertThat(archive).isRegularFile();
        Set<String> names = tarEntryNames(archive);
        assertThat(names)
            .contains("scrubme/config.yaml", "scrubme/SOUL.md",
                "scrubme/memories/MEMORY.md", "scrubme/skills/demo/SKILL.md",
                "scrubme/desktop.json")
            .noneMatch(name -> name.endsWith("/.env") || name.endsWith("/auth.json"));
        String archivedText = tarTextBlob(archive);
        assertThat(archivedText).doesNotContain(leaked).contains("[REDACTED]");
        assertThat(Files.readString(profile.resolve("SOUL.md"))).contains(leaked);
        assertThat(Files.readString(profile.resolve("skills").resolve("demo").resolve("SKILL.md"))).contains(leaked);
    }

    @Test
    void importProfileSafelyCreatesNamedProfileAndSkipsCredentials() throws Exception {
        Path source = tempDir.resolve("profiles").resolve("source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("config.yaml"), "model: gpt-5\n");
        Files.writeString(source.resolve("SOUL.md"), "Imported soul\n");

        Path archive = Path.of((String) profileService.exportProfile(
            "source",
            tempDir.resolve("source.tar.gz").toString(),
            Map.of("desktop.json", "{\"accent\":\"blue\"}")).get("archive"));

        Map<String, Object> result = profileService.importProfile(archive.toString(), "imported");

        Path imported = tempDir.resolve("profiles").resolve("imported");
        assertThat(result).containsEntry("ok", true).containsEntry("name", "imported");
        assertThat(imported).isDirectory();
        assertThat(Files.readString(imported.resolve("config.yaml"))).contains("gpt-5");
        assertThat(Files.readString(imported.resolve("SOUL.md"))).isEqualTo("Imported soul\n");
        assertThat(imported.resolve(".env")).isRegularFile();
        assertThat(imported.resolve("auth.json")).doesNotExist();
        assertThat(result.get("desktop")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.get("desktop")).get("accent")).isEqualTo("blue");
    }

    @Test
    void importProfileRejectsUnsafeArchiveMemberPaths() throws Exception {
        Path archive = tempDir.resolve("unsafe.tar.gz");
        writeUnsafeTar(archive, "../evil.txt", "nope");

        assertThatThrownBy(() -> profileService.importProfile(archive.toString(), "safe"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsafe archive member path");

        assertThat(tempDir.resolve("evil.txt")).doesNotExist();
        assertThat(tempDir.resolve("profiles").resolve("safe")).doesNotExist();
    }

    private static Set<String> tarEntryNames(Path archive) throws Exception {
        Set<String> names = new java.util.LinkedHashSet<>();
        try (TarArchiveInputStream tar = openTar(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static String tarTextBlob(Path archive) throws Exception {
        StringBuilder text = new StringBuilder();
        try (TarArchiveInputStream tar = openTar(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isFile() && entry.getName().matches(".*\\.(md|yaml|json)$")) {
                    text.append(new String(tar.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return text.toString();
    }

    private static TarArchiveInputStream openTar(Path archive) throws Exception {
        return new TarArchiveInputStream(new GzipCompressorInputStream(
            new BufferedInputStream(Files.newInputStream(archive))));
    }

    private static void writeUnsafeTar(Path archive, String name, String content) throws Exception {
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(
            new BufferedOutputStream(Files.newOutputStream(archive))))) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(bytes.length);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
        }
    }

    private static boolean tryCreateSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return false;
        }
    }
}
