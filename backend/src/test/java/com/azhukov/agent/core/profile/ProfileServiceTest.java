package com.azhukov.agent.core.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature 10: Profile management test.
 * Verifies profile creation, listing, switching, and deletion.
 */
class ProfileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createProfileCreatesDirectoryStructure() throws IOException {
        ProfileService service = new ProfileService(tempDir);
        Path profileDir = service.createProfile("coder");

        assertThat(Files.isDirectory(profileDir)).isTrue();
        assertThat(Files.isDirectory(profileDir.resolve("sessions"))).isTrue();
        assertThat(Files.isDirectory(profileDir.resolve("memory"))).isTrue();
        assertThat(Files.isDirectory(profileDir.resolve("skills"))).isTrue();
    }

    @Test
    void createProfileAlreadyExistsThrows() throws IOException {
        ProfileService service = new ProfileService(tempDir);
        service.createProfile("dev");

        assertThatThrownBy(() -> service.createProfile("dev"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void profileExistsReturnsTrueForCreatedProfile() throws IOException {
        ProfileService service = new ProfileService(tempDir);
        service.createProfile("test-profile");

        assertThat(service.profileExists("test-profile")).isTrue();
    }

    @Test
    void profileExistsReturnsFalseForNonExistent() {
        ProfileService service = new ProfileService(tempDir);
        assertThat(service.profileExists("nonexistent")).isFalse();
    }

    @Test
    void listProfilesIncludesDefault() {
        ProfileService service = new ProfileService(tempDir);
        var profiles = service.listProfiles();

        assertThat(profiles).anyMatch(p -> p.name().equals("default"));
    }

    @Test
    void listProfilesIncludesCreatedProfiles() throws IOException {
        ProfileService service = new ProfileService(tempDir);
        service.createProfile("alpha");
        service.createProfile("beta");

        var profiles = service.listProfiles();

        assertThat(profiles).anyMatch(p -> p.name().equals("alpha"));
        assertThat(profiles).anyMatch(p -> p.name().equals("beta"));
    }

    @Test
    void switchProfileChangesActiveProfile() throws IOException {
        ProfileService service = new ProfileService(tempDir);
        service.createProfile("work");

        service.switchProfile("work");

        assertThat(service.getActiveProfile()).isEqualTo("work");
    }

    @Test
    void switchToNonExistentProfileThrows() {
        ProfileService service = new ProfileService(tempDir);

        assertThatThrownBy(() -> service.switchProfile("nonexistent"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void deleteProfileRemovesDirectory() throws IOException {
        ProfileService service = new ProfileService(tempDir);
        service.createProfile("temp-profile");
        assertThat(service.profileExists("temp-profile")).isTrue();

        service.deleteProfile("temp-profile");

        assertThat(service.profileExists("temp-profile")).isFalse();
    }

    @Test
    void deleteDefaultProfileThrows() {
        ProfileService service = new ProfileService(tempDir);

        assertThatThrownBy(() -> service.deleteProfile("default"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cannot delete the default");
    }

    @Test
    void deleteProfileResetsActiveProfile() throws IOException {
        ProfileService service = new ProfileService(tempDir);
        service.createProfile("to-delete");
        service.switchProfile("to-delete");
        assertThat(service.getActiveProfile()).isEqualTo("to-delete");

        service.deleteProfile("to-delete");

        assertThat(service.getActiveProfile()).isEqualTo("default");
    }

    @Test
    void validateProfileNameRejectsInvalidNames() {
        ProfileService service = new ProfileService(tempDir);

        assertThatThrownBy(() -> service.validateProfileName(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateProfileName("UPPERCASE"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateProfileName("has spaces"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateProfileNameRejectsReservedNames() {
        ProfileService service = new ProfileService(tempDir);

        for (String reserved : new String[]{"hermes", "test", "tmp", "root", "sudo"}) {
            assertThatThrownBy(() -> service.validateProfileName(reserved))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        }
    }

    @Test
    void validateProfileNameAcceptsValidNames() {
        ProfileService service = new ProfileService(tempDir);

        service.validateProfileName("default"); // special pass-through
        service.validateProfileName("coder");
        service.validateProfileName("dev-1");
        service.validateProfileName("my_profile");
        service.validateProfileName("a1b2c3");
    }

    @Test
    void getProfileDirReturnsCorrectPath() {
        ProfileService service = new ProfileService(tempDir);

        Path dir = service.getProfileDir("myprofile");
        assertThat(dir).isEqualTo(tempDir.resolve("myprofile"));
    }

    @Test
    void defaultProfileAlwaysInList() throws IOException {
        ProfileService service = new ProfileService(tempDir);
        // Even before creating any profiles, default should be in the list
        var profiles = service.listProfiles();
        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).name()).isEqualTo("default");
    }
}