package com.azhukov.agent.core.profile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Feature 10: Profile management — isolated config directories with own
 * sessions, memory, and skills.
 *
 * Mirrors Hermes hermes_cli/profiles.py.
 * Each profile = isolated config directory with own sessions, memory, skills.
 * Config: agent.profile.name (default "default"), agent.profile.base-dir (default ~/.java-agent/profiles/)
 *
 * Profile directory structure:
 *   {base-dir}/{name}/sessions
 *   {base-dir}/{name}/memory
 *   {base-dir}/{name}/skills
 */
@Slf4j
@Component
public class ProfileService {

    private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    private static final List<String> PROFILE_DIRS = List.of("sessions", "memory", "skills");
    private static final List<String> RESERVED_NAMES = List.of("hermes", "default", "test", "tmp", "root", "sudo");

    private final Path baseDir;
    private String activeProfile;

    public ProfileService() {
        this(Path.of(System.getProperty("user.home"), ".java-agent", "profiles"));
    }

    public ProfileService(Path baseDir) {
        this.baseDir = baseDir;
        this.activeProfile = "default";
    }

    /**
     * Validate a profile name.
     */
    public void validateProfileName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Profile name cannot be empty");
        }
        if (name.equals("default")) {
            return; // special alias
        }
        if (!PROFILE_ID_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Invalid profile name '" + name + "'. Must match [a-z0-9][a-z0-9_-]{0,63}");
        }
        if (RESERVED_NAMES.contains(name)) {
            throw new IllegalArgumentException(
                "Profile name '" + name + "' is reserved");
        }
    }

    /**
     * Get the directory for a profile.
     */
    public Path getProfileDir(String name) {
        validateProfileName(name);
        if (name.equals("default")) {
            return baseDir.resolve("default");
        }
        return baseDir.resolve(name);
    }

    /**
     * Check if a profile exists.
     */
    public boolean profileExists(String name) {
        try {
            return Files.isDirectory(getProfileDir(name));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Create a new profile with the standard directory structure.
     *
     * @param name the profile name
     * @return the path to the created profile directory
     */
    public Path createProfile(String name) throws IOException {
        validateProfileName(name);
        Path profileDir = getProfileDir(name);

        if (Files.exists(profileDir)) {
            throw new IOException("Profile '" + name + "' already exists at " + profileDir);
        }

        Files.createDirectories(profileDir);
        for (String dir : PROFILE_DIRS) {
            Files.createDirectories(profileDir.resolve(dir));
        }

        log.info("Created profile '{}' at {}", name, profileDir);
        return profileDir;
    }

    /**
     * Delete a profile.
     *
     * @param name the profile name
     */
    public void deleteProfile(String name) throws IOException {
        validateProfileName(name);
        if (name.equals("default")) {
            throw new IllegalArgumentException("Cannot delete the default profile");
        }

        Path profileDir = getProfileDir(name);
        if (!Files.isDirectory(profileDir)) {
            throw new IOException("Profile '" + name + "' does not exist");
        }

        deleteRecursively(profileDir);
        log.info("Deleted profile '{}' at {}", name, profileDir);

        if (name.equals(activeProfile)) {
            activeProfile = "default";
        }
    }

    /**
     * List all profiles.
     *
     * @return list of profile info records
     */
    public List<ProfileInfo> listProfiles() {
        List<ProfileInfo> profiles = new ArrayList<>();

        // Always include "default" even if directory doesn't exist yet
        Path defaultDir = baseDir.resolve("default");
        profiles.add(new ProfileInfo("default", defaultDir, Files.isDirectory(defaultDir)));

        if (!Files.isDirectory(baseDir)) {
            return profiles;
        }

        try (Stream<Path> entries = Files.list(baseDir)) {
            entries
                .filter(Files::isDirectory)
                .filter(p -> PROFILE_ID_PATTERN.matcher(p.getFileName().toString()).matches())
                .filter(p -> !p.getFileName().toString().equals("default"))
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    profiles.add(new ProfileInfo(name, p, true));
                });
        } catch (IOException e) {
            log.warn("Failed to list profiles: {}", e.getMessage());
        }

        return profiles;
    }

    /**
     * Switch the active profile.
     *
     * @param name the profile name to switch to
     */
    public void switchProfile(String name) {
        validateProfileName(name);
        if (!profileExists(name)) {
            throw new IllegalArgumentException(
                "Profile '" + name + "' does not exist. Create it first.");
        }
        this.activeProfile = name;
        log.info("Switched active profile to '{}'", name);
    }

    /**
     * Get the active profile name.
     */
    public String getActiveProfile() {
        return activeProfile;
    }

    /**
     * Get the base directory for profiles.
     */
    public Path getBaseDir() {
        return baseDir;
    }

    /**
     * Profile summary information.
     */
    public record ProfileInfo(
        String name,
        Path path,
        boolean exists
    ) {}

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.delete(path);
    }
}